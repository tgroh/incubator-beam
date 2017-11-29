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

package org.apache.beam.runners.reference;

import org.apache.beam.model.fnexecution.v1.BeamFnApi.ProcessBundleDescriptor;
import org.apache.beam.model.pipeline.v1.RunnerApi.PTransform;

/**
 * A collection of {@link PTransform PTransforms} that can be executed as a single unit, their
 * inputs, outputs, and other associated data.
 */
public class PipelineStage {
  private final String id;

  private PipelineStage(String id) {
    this.id = id;
  }

  /**
   * Returns a {@link ProcessBundleDescriptor} containing all of the transforms in this stage.
   */
  public ProcessBundleDescriptor toProcessBundleDescriptor() {
    throw new UnsupportedOperationException("TODO");
  }

  public String getId() {
    return id;
  }
}
