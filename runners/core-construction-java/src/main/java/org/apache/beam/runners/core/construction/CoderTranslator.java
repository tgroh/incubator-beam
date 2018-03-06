/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.beam.runners.core.construction;

import java.util.List;
import org.apache.beam.sdk.annotations.Experimental;
import org.apache.beam.sdk.annotations.Experimental.Kind;
import org.apache.beam.sdk.coders.Coder;

/**
 * An interface that translates coders to components and back.
 *
 * <p>This interface is highly experimental, and incomplete. It is eventually expected to be
 * available for users to register coders they wish to translate by components rather than by java
 * serialization, and thus should not expose any portability data types on its API surface.
 */
@Experimental(Kind.CORE_RUNNERS_ONLY)
public interface CoderTranslator<T extends Coder<?>> {
  /** Get the URN of a {@link Coder}. */
  String getUrn(T from);

  /**
  * Extract all component {@link Coder coders} within a coder.
   */
  List<? extends Coder<?>> getComponents(T from);

  /**
   * Returns the serialized payload that will be provided when deserializing this coder, if any. If
   * there is no payload, a byte array of length 0 should be returned.
   *
   * <p>The default implementation returns a byte array of length zero.
   */
  default byte[] getPayload(T from) {
    return new byte[0];
  }

  /** Create a {@link Coder} from its component {@link Coder coders}. */
  T fromComponents(List<Coder<?>> components, byte[] payload);
}
