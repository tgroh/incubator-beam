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

import com.google.auto.value.AutoValue;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nullable;
import org.apache.beam.runners.direct.DirectRunner.CommittedBundle;
import org.apache.beam.runners.direct.DirectRunner.UncommittedBundle;
import org.apache.beam.sdk.io.BoundedSource;
import org.apache.beam.sdk.io.BoundedSource.BoundedReader;
import org.apache.beam.sdk.io.Read.Bounded;
import org.apache.beam.sdk.transforms.AppliedPTransform;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.util.WindowedValue;
import org.apache.beam.sdk.values.PCollection;

/**
 * A {@link TransformEvaluatorFactory} that produces {@link TransformEvaluator TransformEvaluators}
 * for the {@link Bounded Read.Bounded} primitive {@link PTransform}.
 */
final class BoundedReadEvaluatorFactory implements RootTransformEvaluatorFactory {
  /*
   * An evaluator for a Source is stateful, to ensure data is not read multiple times.
   * Evaluators are cached here to ensure that the reader is not restarted if the evaluator is
   * retriggered.
   */
  private final ConcurrentMap<AppliedPTransform<?, ?, ?>, Queue<? extends BoundedReadEvaluator<?>>>
      sourceEvaluators;
  private final EvaluationContext evaluationContext;

  BoundedReadEvaluatorFactory(EvaluationContext evaluationContext) {
    this.evaluationContext = evaluationContext;
    sourceEvaluators = new ConcurrentHashMap<>();
  }

  @Override
  public List<CommittedBundle<?>> getInitialInputs(AppliedPTransform<?, ?, ?> transform) {
    int availableEvaluators = createEvaluators((AppliedPTransform) transform).size();
    List<CommittedBundle<?>> bundles = new ArrayList<>(availableEvaluators);
    for (int i = 0; i < availableEvaluators; i++) {
      bundles.add(ImpulseBundle.create());
    }
    return bundles;
  }

  /**
   * Get a {@link TransformEvaluator} that produces elements for the provided application of {@link
   * Bounded Read.Bounded}, initializing the queue of evaluators if required.
   */
  private <OutputT> Queue<BoundedReadEvaluator<OutputT>> createEvaluators(
      final AppliedPTransform<?, PCollection<OutputT>, ?> transform) {
    // Key by the application and the context the evaluation is occurring in (which call to
    // Pipeline#run).
    Queue<BoundedReadEvaluator<OutputT>> evaluatorQueue = new ConcurrentLinkedQueue<>();
    // If no queue existed in the evaluators, add an evaluator to initialize the evaluator
    // factory for this transform
    Bounded<OutputT> bound = (Bounded<OutputT>) transform.getTransform();
    BoundedSource<OutputT> source = bound.getSource();
    BoundedReadEvaluator<OutputT> evaluator =
        new BoundedReadEvaluator<>(transform, evaluationContext, source);
    evaluatorQueue.offer(evaluator);
    sourceEvaluators.put(transform, evaluatorQueue);
    return evaluatorQueue;
  }


  @SuppressWarnings({"unchecked", "rawtypes"})
  @Override
  @Nullable
  public <InputT> TransformEvaluator<InputT> forApplication(
      AppliedPTransform<?, ?, ?> application, CommittedBundle<?> inputBundle) throws IOException {
    return (TransformEvaluator<InputT>) sourceEvaluators.get(application).poll();
  }

  @Override
  public void cleanup() {}

  /**
   * A {@link BoundedReadEvaluator} produces elements from an underlying {@link BoundedSource},
   * discarding all input elements. Within the call to {@link #finishBundle()}, the evaluator
   * creates the {@link BoundedReader} and consumes all available input.
   *
   * <p>A {@link BoundedReadEvaluator} should only be created once per {@link BoundedSource}, and
   * each evaluator should only be called once per evaluation of the pipeline. Otherwise, the source
   * may produce duplicate elements.
   */
  private static class BoundedReadEvaluator<OutputT> implements TransformEvaluator<Object> {
    private final AppliedPTransform<?, PCollection<OutputT>, ?> transform;
    private final EvaluationContext evaluationContext;
    /**
     * The source being read from by this {@link BoundedReadEvaluator}. This may not be the same as
     * the source derived from {@link #transform} due to splitting.
     */
    private BoundedSource<OutputT> source;

    public BoundedReadEvaluator(
        AppliedPTransform<?, PCollection<OutputT>, ?> transform,
        EvaluationContext evaluationContext,
        BoundedSource<OutputT> source) {
      this.transform = transform;
      this.evaluationContext = evaluationContext;
      this.source = source;
    }

    @Override
    public void processElement(WindowedValue<Object> element) {}

    @Override
    public TransformResult finishBundle() throws IOException {
      try (final BoundedReader<OutputT> reader =
          source.createReader(evaluationContext.getPipelineOptions())) {
        boolean contentsRemaining = reader.start();
        UncommittedBundle<OutputT> output =
            evaluationContext.createRootBundle(transform.getOutput());
        while (contentsRemaining) {
          output.add(
              WindowedValue.timestampedValueInGlobalWindow(
                  reader.getCurrent(), reader.getCurrentTimestamp()));
          contentsRemaining = reader.advance();
        }
        return StepTransformResult.withHold(transform, BoundedWindow.TIMESTAMP_MAX_VALUE)
            .addOutput(output)
            .build();
      }
    }
  }

  @AutoValue
  abstract static class BoundedSourceShard<T> {
    public static <T> BoundedSourceShard<T> of(BoundedSource<T> source) {
      return new AutoValue_BoundedReadEvaluatorFactory_BoundedSourceShard<>(source);
    }

    abstract BoundedSource<T> getSource();
  }
}
