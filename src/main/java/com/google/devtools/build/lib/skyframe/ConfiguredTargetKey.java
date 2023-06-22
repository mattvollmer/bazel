// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.skyframe;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.devtools.build.lib.util.HashCodes.hashObjects;

import com.google.common.base.MoreObjects;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.skyframe.serialization.DeserializationContext;
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodec;
import com.google.devtools.build.lib.skyframe.serialization.SerializationContext;
import com.google.devtools.build.lib.skyframe.serialization.SerializationException;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.Keep;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * In simple form, a ({@link Label}, {@link BuildConfigurationValue}) pair used to trigger immediate
 * dependency resolution and the rule analysis.
 *
 * <p>In practice, a ({@link Label} and post-transition {@link BuildConfigurationKey}) pair plus a
 * possible execution platform override {@link Label} with special constraints described as follows.
 *
 * <p>A build should not request keys with equal ({@link Label}, {@link BuildConfigurationValue})
 * pairs but different execution platform override {@link Label} if the invoked rule will register
 * actions. (This is potentially OK if all outputs of all registered actions incorporate the
 * execution platform in their name unless the build also requests keys without an override that
 * happen to resolve to the same execution platform.) In practice, this issue has not been seen in
 * any 'real' builds; however, pathologically failure could lead to multiple (potentially different)
 * ConfiguredTarget that have the same ({@link Label}, {@link BuildConfigurationValue}) pair.
 *
 * <p>Note that this key may be used to look up the generating action of an artifact.
 *
 * <p>TODO(blaze-configurability-team): Consider just using BuildOptions over a
 * BuildConfigurationKey.
 */
public class ConfiguredTargetKey implements ActionLookupKey {
  /**
   * Cache so that the number of ConfiguredTargetKey instances is {@code O(configured targets)} and
   * not {@code O(edges between configured targets)}.
   */
  private static final SkyKey.SkyKeyInterner<ConfiguredTargetKey> interner = SkyKey.newInterner();

  private final Label label;
  @Nullable private final BuildConfigurationKey configurationKey;
  private final transient int hashCode;

  private ConfiguredTargetKey(
      Label label, @Nullable BuildConfigurationKey configurationKey, int hashCode) {
    this.label = label;
    this.configurationKey = configurationKey;
    this.hashCode = hashCode;
  }

  @Override
  public final SkyFunctionName functionName() {
    return SkyFunctions.CONFIGURED_TARGET;
  }

  @Override
  public SkyKeyInterner<?> getSkyKeyInterner() {
    return interner;
  }

  @Override
  public Label getLabel() {
    return label;
  }

  @Nullable
  @Override
  public final BuildConfigurationKey getConfigurationKey() {
    return configurationKey;
  }

  @Override
  public final ConfiguredTargetKey toKey() {
    return this;
  }

  @Nullable
  public Label getExecutionPlatformLabel() {
    return null;
  }

  public final String prettyPrint() {
    if (getLabel() == null) {
      return "null";
    }
    return String.format("%s (%s)", getLabel(), formatConfigurationKey(configurationKey));
  }

  @Override
  public final int hashCode() {
    return hashCode;
  }

  @Override
  public final boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof ConfiguredTargetKey)) {
      return false;
    }
    ConfiguredTargetKey other = (ConfiguredTargetKey) obj;
    return hashCode == other.hashCode
        && getLabel().equals(other.getLabel())
        && Objects.equals(configurationKey, other.configurationKey)
        && Objects.equals(getExecutionPlatformLabel(), other.getExecutionPlatformLabel());
  }

  @Override
  public final String toString() {
    // TODO(b/162809183): consider reverting to less verbose toString when bug is resolved.
    MoreObjects.ToStringHelper helper =
        MoreObjects.toStringHelper(this).add("label", getLabel()).add("config", configurationKey);
    if (getExecutionPlatformLabel() != null) {
      helper.add("executionPlatformLabel", getExecutionPlatformLabel());
    }
    return helper.toString();
  }

  private static final class ToolchainDependencyConfiguredTargetKey extends ConfiguredTargetKey {
    private final Label executionPlatformLabel;

    private ToolchainDependencyConfiguredTargetKey(
        Label label,
        @Nullable BuildConfigurationKey configurationKey,
        int hashCode,
        Label executionPlatformLabel) {
      super(label, configurationKey, hashCode);
      this.executionPlatformLabel = checkNotNull(executionPlatformLabel);
    }

    @Override
    public Label getExecutionPlatformLabel() {
      return executionPlatformLabel;
    }
  }

  public Builder toBuilder() {
    return builder()
        .setConfigurationKey(configurationKey)
        .setLabel(getLabel())
        .setExecutionPlatformLabel(getExecutionPlatformLabel());
  }

  /** Returns a new {@link Builder} to create instances of {@link ConfiguredTargetKey}. */
  public static Builder builder() {
    return new Builder();
  }

  /** Returns the {@link ConfiguredTargetKey} that owns {@code configuredTarget}. */
  public static ConfiguredTargetKey fromConfiguredTarget(ConfiguredTarget configuredTarget) {
    // If configuredTarget is a MergedConfiguredTarget unwraps it first. MergedConfiguredTarget is
    // ephemeral and does not have a directly corresponding entry in Skyframe.
    //
    // The cast exists because the key passes through parts of analysis that work on both aspects
    // and configured targets. This process discards the key's specific type information.
    return (ConfiguredTargetKey) configuredTarget.unwrapIfMerged().getKeyOrProxy();
  }

  /** A helper class to create instances of {@link ConfiguredTargetKey}. */
  public static final class Builder {
    private Label label = null;
    private BuildConfigurationKey configurationKey = null;
    private Label executionPlatformLabel = null;

    private Builder() {}

    /** Sets the label for the target. */
    @CanIgnoreReturnValue
    public Builder setLabel(Label label) {
      this.label = label;
      return this;
    }

    /** Sets the {@link BuildConfigurationValue} for the configured target. */
    @CanIgnoreReturnValue
    public Builder setConfiguration(@Nullable BuildConfigurationValue buildConfiguration) {
      return setConfigurationKey(buildConfiguration == null ? null : buildConfiguration.getKey());
    }

    /** Sets the configuration key for the configured target. */
    @CanIgnoreReturnValue
    public Builder setConfigurationKey(@Nullable BuildConfigurationKey configurationKey) {
      this.configurationKey = configurationKey;
      return this;
    }

    /**
     * Sets the execution platform {@link Label} this configured target should use for toolchain
     * resolution. When present, this overrides the normally determined execution platform.
     */
    @CanIgnoreReturnValue
    public Builder setExecutionPlatformLabel(@Nullable Label executionPlatformLabel) {
      this.executionPlatformLabel = executionPlatformLabel;
      return this;
    }

    /** Builds a new {@link ConfiguredTargetKey} based on the supplied data. */
    public ConfiguredTargetKey build() {
      int hashCode = computeHashCode(label, configurationKey, executionPlatformLabel);
      return interner.intern(
          executionPlatformLabel == null
              ? new ConfiguredTargetKey(label, configurationKey, hashCode)
              : new ToolchainDependencyConfiguredTargetKey(
                  label, configurationKey, hashCode, executionPlatformLabel));
    }
  }

  private static int computeHashCode(
      Label label,
      @Nullable BuildConfigurationKey configurationKey,
      @Nullable Label executionPlatformLabel) {
    return hashObjects(label, configurationKey, executionPlatformLabel);
  }

  private static String formatConfigurationKey(@Nullable BuildConfigurationKey key) {
    if (key == null) {
      return "null";
    }
    return key.getOptions().checksum();
  }

  /**
   * Codec for all {@link ConfiguredTargetKey} subtypes.
   *
   * <p>By design, {@link ProxyConfiguredTargetKey} serializes as a key without delegation. Upon
   * deserialization, if the key is locally delegated, it becomes delegating again due to interning.
   * If not, it deserializes to the appropriate non-delegating key.
   */
  @Keep
  private static class ConfiguredTargetKeyCodec implements ObjectCodec<ConfiguredTargetKey> {
    @Override
    public Class<ConfiguredTargetKey> getEncodedClass() {
      return ConfiguredTargetKey.class;
    }

    @Override
    public void serialize(
        SerializationContext context, ConfiguredTargetKey key, CodedOutputStream codedOut)
        throws SerializationException, IOException {
      context.serialize(key.getLabel(), codedOut);
      context.serialize(key.getConfigurationKey(), codedOut);
      context.serialize(key.getExecutionPlatformLabel(), codedOut);
    }

    @Override
    public ConfiguredTargetKey deserialize(DeserializationContext context, CodedInputStream codedIn)
        throws SerializationException, IOException {
      return builder()
          .setLabel(context.deserialize(codedIn))
          .setConfigurationKey(context.deserialize(codedIn))
          .setExecutionPlatformLabel(context.deserialize(codedIn))
          .build();
    }
  }
}
