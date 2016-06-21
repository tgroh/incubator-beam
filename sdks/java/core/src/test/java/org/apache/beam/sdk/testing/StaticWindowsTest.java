package org.apache.beam.sdk.testing;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.GlobalWindow;
import org.apache.beam.sdk.transforms.windowing.IntervalWindow;
import org.apache.beam.sdk.transforms.windowing.WindowFn;

import com.google.common.collect.ImmutableList;

import org.hamcrest.Matchers;
import org.joda.time.Instant;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link StaticWindows}.
 */
@RunWith(JUnit4.class)
public class StaticWindowsTest {
  @Rule
  public ExpectedException thrown = ExpectedException.none();

  private final IntervalWindow first = new IntervalWindow(new Instant(0), new Instant(100_000L));
  private final IntervalWindow second =
      new IntervalWindow(new Instant(1_000_000L), GlobalWindow.INSTANCE.maxTimestamp());

  @Test
  public void singleWindowSucceeds() throws Exception {
    WindowFn<Object, BoundedWindow> fn = StaticWindows.of(IntervalWindow.getCoder(), first);
    assertThat(fn.assignsToSingleWindow(), is(true));
    assertThat(WindowFnTestUtils.assignedWindows(fn, 100L),
        Matchers.<BoundedWindow>contains(first));
    assertThat(WindowFnTestUtils.assignedWindows(fn, -100L),
        Matchers.<BoundedWindow>contains(first));
  }

  @Test
  public void multipleWindowsSucceeds() throws Exception {
    WindowFn<Object, BoundedWindow> fn =
        StaticWindows.of(IntervalWindow.getCoder(), ImmutableList.of(first, second));
    assertThat(fn.assignsToSingleWindow(), is(false));
    assertThat(WindowFnTestUtils.assignedWindows(fn, 100L),
        Matchers.<BoundedWindow>containsInAnyOrder(first, second));
    assertThat(WindowFnTestUtils.assignedWindows(fn, 1_000_000_000L),
        Matchers.<BoundedWindow>containsInAnyOrder(first, second));
    assertThat(WindowFnTestUtils.assignedWindows(fn, -100L),
        Matchers.<BoundedWindow>containsInAnyOrder(first, second));
  }

  @Test
  public void getSideInputWindowIdentity() {
    WindowFn<Object, BoundedWindow> fn =
        StaticWindows.of(IntervalWindow.getCoder(), ImmutableList.of(first, second));

    assertThat(fn.getSideInputWindow(first), Matchers.<BoundedWindow>equalTo(first));
    assertThat(fn.getSideInputWindow(second), Matchers.<BoundedWindow>equalTo(second));
  }

  @Test
  public void getSideInputWindowNotPresent() {
    WindowFn<Object, BoundedWindow> fn =
        StaticWindows.of(IntervalWindow.getCoder(), ImmutableList.of(second));
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("contains");
    fn.getSideInputWindow(first);
  }

  @Test
  public void emptyIterableThrows() {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("may not be empty");
    StaticWindows.of(GlobalWindow.Coder.INSTANCE, ImmutableList.<GlobalWindow>of());
  }
}
