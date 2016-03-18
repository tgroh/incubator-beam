/*
 * Copyright (C) 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.dataflow.sdk.runners.inprocess;

import com.google.cloud.dataflow.sdk.Pipeline;
import com.google.cloud.dataflow.sdk.runners.inprocess.InMemoryWatermarkManager.FiredTimers;
import com.google.cloud.dataflow.sdk.runners.inprocess.InProcessPipelineRunner.CommittedBundle;
import com.google.cloud.dataflow.sdk.transforms.AppliedPTransform;
import com.google.cloud.dataflow.sdk.util.KeyedWorkItem;
import com.google.cloud.dataflow.sdk.util.KeyedWorkItems;
import com.google.cloud.dataflow.sdk.util.TimeDomain;
import com.google.cloud.dataflow.sdk.util.TimerInternals.TimerData;
import com.google.cloud.dataflow.sdk.util.WindowedValue;
import com.google.cloud.dataflow.sdk.values.PCollection;
import com.google.cloud.dataflow.sdk.values.PValue;
import com.google.common.base.MoreObjects;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

import org.joda.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.Nullable;

/**
 * An {@link InProcessExecutor} that uses an underlying {@link ExecutorService} and
 * {@link InProcessEvaluationContext} to execute a {@link Pipeline}.
 */
final class ExecutorServiceParallelExecutor implements InProcessExecutor {
  private static final Logger LOG = LoggerFactory.getLogger(ExecutorServiceParallelExecutor.class);

  private final ExecutorService monitorExecutor;
  private final ExecutorService executorService;

  private final Map<PValue, Collection<AppliedPTransform<?, ?, ?>>> valueToConsumers;
  private final Set<PValue> keyedPValues;
  private final TransformEvaluatorRegistry registry;
  private final InProcessEvaluationContext evaluationContext;

  private final ConcurrentMap<StepAndKey, TransformExecutorService> currentEvaluations;
  private final ConcurrentMap<TransformExecutor<?>, Boolean> scheduledExecutors;

  private final Queue<ExecutorUpdate> allUpdates;
  private final BlockingQueue<VisibleExecutorUpdate> visibleUpdates;

  private final TransformExecutorService parallelExecutorService;
  private final CompletionCallback defaultCompletionCallback;

  private Collection<AppliedPTransform<?, ?, ?>> rootNodes;

  public static ExecutorServiceParallelExecutor create(
      ExecutorService executorService,
      Map<PValue, Collection<AppliedPTransform<?, ?, ?>>> valueToConsumers,
      Set<PValue> keyedPValues,
      TransformEvaluatorRegistry registry,
      InProcessEvaluationContext context) {
    return new ExecutorServiceParallelExecutor(
        executorService, valueToConsumers, keyedPValues, registry, context);
  }

  private ExecutorServiceParallelExecutor(
      ExecutorService executorService,
      Map<PValue, Collection<AppliedPTransform<?, ?, ?>>> valueToConsumers,
      Set<PValue> keyedPValues,
      TransformEvaluatorRegistry registry,
      InProcessEvaluationContext context) {
    this.monitorExecutor = Executors.newSingleThreadExecutor();
    this.executorService = executorService;
    this.valueToConsumers = valueToConsumers;
    this.keyedPValues = keyedPValues;
    this.registry = registry;
    this.evaluationContext = context;

    currentEvaluations = new ConcurrentHashMap<>();
    scheduledExecutors = new ConcurrentHashMap<>();

    this.allUpdates = new ConcurrentLinkedQueue<>();
    this.visibleUpdates = new ArrayBlockingQueue<>(20);

    parallelExecutorService =
        TransformExecutorServices.parallel(executorService, scheduledExecutors);
    defaultCompletionCallback = new DefaultCompletionCallback();
  }

  @Override
  public void start(Collection<AppliedPTransform<?, ?, ?>> roots) {
    rootNodes = ImmutableList.copyOf(roots);
    Runnable monitorRunnable = new MonitorRunnable();
    executorService.submit(monitorRunnable);
  }

  @SuppressWarnings("unchecked")
  public void scheduleConsumption(
      AppliedPTransform<?, ?, ?> consumer,
      @Nullable CommittedBundle<?> bundle,
      CompletionCallback onComplete) {
    evaluateBundle(consumer, bundle, onComplete);
  }

  private <T> void evaluateBundle(
      final AppliedPTransform<?, ?, ?> transform,
      @Nullable final CommittedBundle<T> bundle,
      final CompletionCallback onComplete) {
    TransformExecutorService transformExecutor;
    if (bundle != null && isKeyed(bundle.getPCollection())) {
      final StepAndKey stepAndKey =
          StepAndKey.of(transform, bundle == null ? null : bundle.getKey());
      transformExecutor = getSerialExecutorService(stepAndKey);
    } else {
      transformExecutor = parallelExecutorService;
    }
    TransformExecutor<T> callable =
        TransformExecutor.create(
            registry, evaluationContext, bundle, transform, onComplete, transformExecutor);
    transformExecutor.schedule(callable);
  }

  private boolean isKeyed(PValue pvalue) {
    return keyedPValues.contains(pvalue);
  }

  private void scheduleConsumers(CommittedBundle<?> bundle) {
    for (AppliedPTransform<?, ?, ?> consumer : valueToConsumers.get(bundle.getPCollection())) {
      scheduleConsumption(consumer, bundle, defaultCompletionCallback);
    }
  }

  private TransformExecutorService getSerialExecutorService(StepAndKey stepAndKey) {
    if (!currentEvaluations.containsKey(stepAndKey)) {
      currentEvaluations.putIfAbsent(
          stepAndKey, TransformExecutorServices.serial(executorService, scheduledExecutors));
    }
    return currentEvaluations.get(stepAndKey);
  }

  @Override
  public void awaitCompletion() throws Throwable {
    VisibleExecutorUpdate update;
    do {
      update = visibleUpdates.take();
      if (update.throwable.isPresent()) {
        if (update.throwable.get() instanceof Exception) {
          if (update.transform.isPresent()) {

            LOG.error(
                "Unhandled Exception while evaluating {}",
                update.transform.get().getFullName(),
                update.throwable.get());
          }
          throw update.throwable.get();
        } else {
          throw update.throwable.get();
        }
      }
    } while (!update.isDone());
    executorService.shutdown();
  }

  /**
   * The default {@link CompletionCallback}. The default completion callback is used to complete
   * transform evaluations that are triggered due to the arrival of elements from an upstream
   * transform, or for a source transform.
   */
  private class DefaultCompletionCallback implements CompletionCallback {
    @Override
    public void handleResult(
        CommittedBundle<?> inputBundle,
        AppliedPTransform<?, ?, ?> transform,
        InProcessTransformResult result) {
      Iterable<? extends CommittedBundle<?>> resultBundles =
          evaluationContext.handleResult(inputBundle, Collections.<TimerData>emptyList(), result);
      for (CommittedBundle<?> outputBundle : resultBundles) {
        allUpdates.offer(ExecutorUpdate.fromBundle(transform, outputBundle));
      }
    }

    @Override
    public void handleThrowable(
        CommittedBundle<?> inputBundle, AppliedPTransform<?, ?, ?> transform, Throwable t) {
      allUpdates.offer(ExecutorUpdate.fromThrowable(transform, t));
    }
  }

  /**
   * A {@link CompletionCallback} where the completed bundle was produced to deliver some collection
   * of {@link TimerData timers}. When the evaluator completes successfully, reports all of the
   * timers used to create the input to the {@link InProcessEvaluationContext evaluation context}
   * as part of the result.
   */
  private class TimerCompletionCallback implements CompletionCallback {
    private final Iterable<TimerData> timers;

    private TimerCompletionCallback(Iterable<TimerData> timers) {
      this.timers = timers;
    }

    @Override
    public void handleResult(
        CommittedBundle<?> inputBundle,
        AppliedPTransform<?, ?, ?> transform,
        InProcessTransformResult result) {
      Iterable<? extends CommittedBundle<?>> resultBundles =
          evaluationContext.handleResult(inputBundle, timers, result);
      for (CommittedBundle<?> outputBundle : resultBundles) {
        allUpdates.offer(ExecutorUpdate.fromBundle(transform, outputBundle));
      }
    }

    @Override
    public void handleThrowable(
        CommittedBundle<?> inputBundle, AppliedPTransform<?, ?, ?> transform, Throwable t) {
      allUpdates.offer(ExecutorUpdate.fromThrowable(transform, t));
    }
  }

  /**
   * An internal status update on the state of the executor.
   *
   * Used to signal when the executor should be shut down (due to an exception).
   */
  private static class ExecutorUpdate {
    private final AppliedPTransform<?, ?, ?> transform;
    private final Optional<? extends CommittedBundle<?>> bundle;
    private final Optional<? extends Throwable> throwable;

    public static ExecutorUpdate fromBundle(
        AppliedPTransform<?, ?, ?> transform, CommittedBundle<?> bundle) {
      return new ExecutorUpdate(transform, bundle, null);
    }

    public static ExecutorUpdate fromThrowable(AppliedPTransform<?, ?, ?> transform, Throwable t) {
      return new ExecutorUpdate(transform, null, t);
    }

    private ExecutorUpdate(
        AppliedPTransform<?, ?, ?> transform,
        CommittedBundle<?> producedBundle,
        Throwable throwable) {
      this.transform = transform;
      this.bundle = Optional.fromNullable(producedBundle);
      this.throwable = Optional.fromNullable(throwable);
    }

    public Optional<? extends CommittedBundle<?>> getBundle() {
      return bundle;
    }

    public Optional<? extends Throwable> getException() {
      return throwable;
    }

    public AppliedPTransform<?, ?, ?> getTransform() {
      return transform;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(ExecutorUpdate.class)
          .add("bundle", bundle)
          .add("transform", transform)
          .add("exception", throwable)
          .toString();
    }
  }

  /**
   * An update of interest to the user. Used in {@link #awaitCompletion} to decide whether to
   * return normally or throw an exception.
   */
  private static class VisibleExecutorUpdate {
    private final Optional<AppliedPTransform<?, ?, ?>> transform;
    private final Optional<? extends Throwable> throwable;
    private final boolean done;

    public static VisibleExecutorUpdate fromThrowable(
        AppliedPTransform<?, ?, ?> transform, Throwable e) {
      return new VisibleExecutorUpdate(false, transform, e);
    }

    public static VisibleExecutorUpdate finished() {
      return new VisibleExecutorUpdate(true, null, null);
    }

    private VisibleExecutorUpdate(
        boolean done,
        @Nullable AppliedPTransform<?, ?, ?> transform,
        @Nullable Throwable exception) {
      this.transform = Optional.<AppliedPTransform<?, ?, ?>>fromNullable(transform);
      this.throwable = Optional.fromNullable(exception);
      this.done = done;
    }

    public boolean isDone() {
      return done;
    }
  }

  private class MonitorRunnable implements Runnable {
    private final String runnableName =
        String.format(
            "%s$%s-monitor",
            evaluationContext.getPipelineOptions().getAppName(),
            ExecutorServiceParallelExecutor.class.getSimpleName());

    @Override
    public void run() {
      String oldName = Thread.currentThread().getName();
      Thread.currentThread().setName(runnableName);
      try {
        ExecutorUpdate update = allUpdates.poll();
        if (update != null) {
          LOG.debug("Executor Update: {}", update);
          if (update.getBundle().isPresent()) {
            scheduleConsumers(update.getBundle().get());
          } else if (update.getException().isPresent()) {
            visibleUpdates.offer(
                VisibleExecutorUpdate.fromThrowable(
                    update.getTransform(), update.getException().get()));
          }
        }
        fireTimers();
        mightNeedMoreWork();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        LOG.error("Monitor died due to being interrupted");
        while (!visibleUpdates.offer(VisibleExecutorUpdate.fromThrowable(null, e))) {
          visibleUpdates.poll();
        }
      } catch (Throwable t) {
        LOG.error("Monitor thread died due to throwable", t);
        while (!visibleUpdates.offer(VisibleExecutorUpdate.fromThrowable(null, t))) {
          visibleUpdates.poll();
        }
      } finally {
        if (!shouldShutdown()) {
          // The monitor thread should always be scheduled; but we only need to be scheduled once;
          // Allow other work to be done before reexecuting
          try {
            monitorExecutor.submit(this);
          } catch (Throwable t) {
            LOG.error("Error while rescheduling monitor", t);
          }
        }
        Thread.currentThread().setName(oldName);
      }
    }

    private void fireTimers() throws Exception {
      try {
        for (Map.Entry<AppliedPTransform<?, ?, ?>, Map<Object, FiredTimers>> transformTimers :
            evaluationContext.extractFiredTimers().entrySet()) {
          AppliedPTransform<?, ?, ?> transform = transformTimers.getKey();
          for (Map.Entry<Object, FiredTimers> keyTimers : transformTimers.getValue().entrySet()) {
            for (TimeDomain domain : TimeDomain.values()) {
              Collection<TimerData> delivery = keyTimers.getValue().getTimers(domain);
              if (delivery.isEmpty()) {
                continue;
              }
              KeyedWorkItem<Object, Object> work =
                  KeyedWorkItems.timersWorkItem(keyTimers.getKey(), delivery);
              @SuppressWarnings({"unchecked", "rawtypes"})
              CommittedBundle<?> bundle =
                  evaluationContext
                      .createKeyedBundle(
                          null, keyTimers.getKey(), (PCollection) transform.getInput())
                      .add(WindowedValue.valueInEmptyWindows(work))
                      .commit(Instant.now());
              scheduleConsumption(transform, bundle, new TimerCompletionCallback(delivery));
            }
          }
        }
      } catch (Exception e) {
        LOG.error("Internal Error while delivering timers", e);
        throw e;
      }
    }

    private boolean shouldShutdown() {
      if (evaluationContext.isDone()) {
        LOG.debug("Pipeline is finished. Shutting down. {}");
        while (!visibleUpdates.offer(VisibleExecutorUpdate.finished())) {
          visibleUpdates.poll();
        }
        executorService.shutdown();
        return true;
      }
      return false;
    }

    private void mightNeedMoreWork() {
      synchronized (scheduledExecutors) {
        for (TransformExecutor<?> executor : scheduledExecutors.keySet()) {
          Thread thread = executor.getThread();
          if (thread != null) {
            switch (thread.getState()) {
              case BLOCKED:
              case WAITING:
              case TERMINATED:
              case TIMED_WAITING:
                break;
              default:
                return;
            }
          }
        }
      }
      // All current TransformExecutors are blocked; add more work from the roots.
      for (AppliedPTransform<?, ?, ?> root : rootNodes) {
        scheduleConsumption(root, null, defaultCompletionCallback);
      }
    }
  }
}
