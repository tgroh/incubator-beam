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
package org.apache.beam.runners.direct;

import org.apache.beam.runners.direct.InProcessPipelineRunner.CommittedBundle;
import org.apache.beam.sdk.util.WindowedValue;

import javax.annotation.Nullable;

/**
 * An evaluator of a specific application of a transform.
 *
 * <p>A {@link TransformEvaluator} will be used for an arbitrary number of bundles. The methods of
 * {@link TransformEvaluator} will always be invoked by a single thread.
 *
 * @param <InputT> the type of elements that will be passed to {@link #processElement}
 */
public interface TransformEvaluator<InputT> {
  /**
   *
   * @param inputBundle
   */
  void startBundle(@Nullable CommittedBundle<InputT> inputBundle);

  /**
   * Process an element in the input {@link CommittedBundle}.
   *
   * @param element the element to process
   */
  void processElement(WindowedValue<InputT> element) throws Exception;

  /**
   * Finish processing the bundle of this {@link TransformEvaluator}.
   *
   * After {@link #finishBundle()} is called, the {@link TransformEvaluator} will not be reused,
   * and no more elements will be processed.
   *
   * @return an {@link InProcessTransformResult} containing the results of this bundle evaluation.
   */
  InProcessTransformResult finishBundle() throws Exception;
}
