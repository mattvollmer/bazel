// Copyright 2023 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.actions;

import com.google.devtools.build.lib.actions.CommandLineItem.ExceptionlessMapFn;
import com.google.devtools.build.lib.actions.CommandLineItem.MapFn;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Support for mapping config parts of exec paths in an action's command line as well as when
 * staging its inputs and outputs for execution, with the aim of making the resulting {@link Spawn}
 * more cacheable.
 *
 * <p>Actions that want to support path mapping should use {@link
 * com.google.devtools.build.lib.analysis.actions.PathMappers}.
 *
 * <p>An example of an implementing class is {@link
 * com.google.devtools.build.lib.analysis.actions.StrippingPathMapper}, which removes the config
 * part (e.g. "k8-fastbuild") from exec paths to allow for cross-configuration cache hits.
 */
public interface PathMapper {
  /** Returns the exec path with the path mapping applied. */
  PathFragment map(PathFragment execPath);

  /** Returns the exec path of the input with the path mapping applied. */
  default String getMappedExecPathString(ActionInput artifact) {
    return map(artifact.getExecPath()).getPathString();
  }

  /**
   * We don't yet have a Starlark API for mapping paths in command lines. Simple Starlark calls like
   * {@code args.add(arg_name, file_path} are automatically handled. But calls that involve custom
   * Starlark code require deeper API support that remains a TODO.
   *
   * <p>This method allows implementations to hard-code support for specific command line entries
   * for specific Starlark actions.
   */
  default List<String> mapCustomStarlarkArgs(List<String> args) {
    return args;
  }

  /**
   * Returns the {@link MapFn} to apply to a vector argument with the given previous String argument
   * in a {@link com.google.devtools.build.lib.analysis.actions.CustomCommandLine}.
   *
   * <p>For example, if the previous argument is {@code "--foo"}, this method should return a {@link
   * MapFn} that maps the next arguments to the correct path, potentially mapping them if "--foo"
   * requires it.
   *
   * <p>This is used to map paths obtained via location expansion in native rules, which returns a
   * list of strings rather than a structured command line.
   *
   * <p>By default, this method returns {@link MapFn#DEFAULT}.
   */
  default ExceptionlessMapFn<Object> getMapFn(@Nullable String previousFlag) {
    return MapFn.DEFAULT;
  }

  /**
   * Returns {@code true} if the mapper is known to map all paths identically.
   *
   * <p>Can be used by actions to skip additional work that isn't needed if path mapping is not
   * enabled.
   */
  default boolean isNoop() {
    return this == NOOP;
  }

  /** A {@link PathMapper} that doesn't change paths. */
  PathMapper NOOP = execPath -> execPath;
}
