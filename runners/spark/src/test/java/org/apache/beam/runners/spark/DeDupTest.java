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

package org.apache.beam.runners.spark;

import com.google.cloud.dataflow.sdk.Pipeline;
import com.google.cloud.dataflow.sdk.coders.StringUtf8Coder;
import com.google.cloud.dataflow.sdk.testing.PAssert;
import com.google.cloud.dataflow.sdk.transforms.Create;
import com.google.cloud.dataflow.sdk.transforms.RemoveDuplicates;
import com.google.cloud.dataflow.sdk.values.PCollection;
import com.google.common.collect.ImmutableSet;
import org.apache.beam.runners.spark.translation.SparkPipelineOptionsFactory;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * A test based on {@code DeDupExample} from the SDK.
 */
public class DeDupTest {

  private static final String[] LINES_ARRAY = {
      "hi there", "hello", "hi there",
      "hi", "hello"};
  private static final List<String> LINES = Arrays.asList(LINES_ARRAY);
  private static final Set<String> EXPECTED_SET =
      ImmutableSet.of("hi there", "hi", "hello");

  @Test
  public void testRun() throws Exception {
    SparkPipelineOptions options = SparkPipelineOptionsFactory.create();
    options.setRunner(SparkPipelineRunner.class);
    Pipeline p = Pipeline.create(options);
    PCollection<String> input = p.apply(Create.of(LINES)).setCoder(StringUtf8Coder.of());
    PCollection<String> output = input.apply(RemoveDuplicates.<String>create());

    PAssert.that(output).containsInAnyOrder(EXPECTED_SET);

    EvaluationResult res = SparkPipelineRunner.create().run(p);
    res.close();
  }
}
