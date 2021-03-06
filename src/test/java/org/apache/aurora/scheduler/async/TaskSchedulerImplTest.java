/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.aurora.scheduler.async;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableSet;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.twitter.common.quantity.Amount;
import com.twitter.common.quantity.Time;
import com.twitter.common.stats.Stats;
import com.twitter.common.stats.StatsProvider;
import com.twitter.common.testing.easymock.EasyMockTest;
import com.twitter.common.util.Clock;
import com.twitter.common.util.testing.FakeClock;

import org.apache.aurora.gen.AssignedTask;
import org.apache.aurora.gen.HostAttributes;
import org.apache.aurora.gen.Identity;
import org.apache.aurora.gen.JobKey;
import org.apache.aurora.gen.MaintenanceMode;
import org.apache.aurora.gen.ScheduledTask;
import org.apache.aurora.gen.TaskConfig;
import org.apache.aurora.scheduler.HostOffer;
import org.apache.aurora.scheduler.async.TaskScheduler.TaskSchedulerImpl;
import org.apache.aurora.scheduler.async.preemptor.Preemptor;
import org.apache.aurora.scheduler.base.Query;
import org.apache.aurora.scheduler.base.TaskGroupKey;
import org.apache.aurora.scheduler.base.Tasks;
import org.apache.aurora.scheduler.events.EventSink;
import org.apache.aurora.scheduler.events.PubsubEvent.TaskStateChange;
import org.apache.aurora.scheduler.events.PubsubEventModule;
import org.apache.aurora.scheduler.filter.AttributeAggregate;
import org.apache.aurora.scheduler.filter.SchedulingFilter.ResourceRequest;
import org.apache.aurora.scheduler.state.PubsubTestUtil;
import org.apache.aurora.scheduler.state.StateManager;
import org.apache.aurora.scheduler.state.TaskAssigner;
import org.apache.aurora.scheduler.state.TaskAssigner.Assignment;
import org.apache.aurora.scheduler.state.TaskAssigner.Assignment.Result;
import org.apache.aurora.scheduler.storage.AttributeStore;
import org.apache.aurora.scheduler.storage.Storage;
import org.apache.aurora.scheduler.storage.Storage.MutableStoreProvider;
import org.apache.aurora.scheduler.storage.Storage.MutateWork;
import org.apache.aurora.scheduler.storage.entities.IHostAttributes;
import org.apache.aurora.scheduler.storage.entities.IScheduledTask;
import org.apache.aurora.scheduler.storage.mem.MemStorage;
import org.apache.aurora.scheduler.storage.testing.StorageTestUtil;
import org.apache.mesos.Protos.TaskInfo;
import org.easymock.Capture;
import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

import static org.apache.aurora.gen.ScheduleStatus.PENDING;
import static org.apache.aurora.gen.ScheduleStatus.THROTTLED;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TaskSchedulerImplTest extends EasyMockTest {

  private static final IScheduledTask TASK_A = makeTask("a");
  private static final IScheduledTask TASK_B = makeTask("b");
  private static final HostOffer OFFER = new HostOffer(
      Offers.makeOffer("OFFER_A", "HOST_A"),
      IHostAttributes.build(new HostAttributes().setMode(MaintenanceMode.NONE)));

  private static final TaskGroupKey GROUP_A = TaskGroupKey.from(TASK_A.getAssignedTask().getTask());
  private static final TaskGroupKey GROUP_B = TaskGroupKey.from(TASK_B.getAssignedTask().getTask());

  private StorageTestUtil storageUtil;
  private StateManager stateManager;
  private TaskAssigner assigner;
  private OfferManager offerManager;
  private TaskScheduler scheduler;
  private FakeClock clock;
  private Preemptor preemptor;
  private Amount<Long, Time> reservationDuration;
  private Amount<Long, Time> halfReservationDuration;
  private EventSink eventSink;
  private AttributeAggregate emptyJob;

  @Before
  public void setUp() throws Exception {
    storageUtil = new StorageTestUtil(this);
    stateManager = createMock(StateManager.class);
    assigner = createMock(TaskAssigner.class);
    offerManager = createMock(OfferManager.class);
    reservationDuration = Amount.of(2L, Time.MINUTES);
    halfReservationDuration = Amount.of(1L, Time.MINUTES);
    clock = new FakeClock();
    clock.setNowMillis(0);
    preemptor = createMock(Preemptor.class);

    Injector injector = getInjector(storageUtil.storage);
    scheduler = injector.getInstance(TaskScheduler.class);
    eventSink = PubsubTestUtil.startPubsub(injector);
    emptyJob = new AttributeAggregate(
        Suppliers.ofInstance(ImmutableSet.<IScheduledTask>of()),
        createMock(AttributeStore.class));
  }

  private Injector getInjector(final Storage storageImpl) {
    return Guice.createInjector(
        new PubsubEventModule(false),
        new AbstractModule() {
          @Override
          protected void configure() {
            bind(Preemptor.class).toInstance(preemptor);
            AsyncModule.bindTaskScheduler(binder(), reservationDuration);
            bind(OfferManager.class).toInstance(offerManager);
            bind(StateManager.class).toInstance(stateManager);
            bind(TaskAssigner.class).toInstance(assigner);
            bind(Clock.class).toInstance(clock);
            bind(Storage.class).toInstance(storageImpl);
            bind(StatsProvider.class).toInstance(Stats.STATS_PROVIDER);
          }
        });
  }

  private void expectTaskStillPendingQuery(IScheduledTask task) {
    storageUtil.expectTaskFetch(
        Query.taskScoped(Tasks.id(task)).byStatus(PENDING),
        ImmutableSet.of(task));
  }

  private void expectAssigned(IScheduledTask task) {
    expect(assigner.maybeAssign(
        storageUtil.mutableStoreProvider,
        OFFER,
        new ResourceRequest(task.getAssignedTask().getTask(), Tasks.id(task), emptyJob)))
        .andReturn(Assignment.success(TaskInfo.getDefaultInstance()));
  }

  @Test
  public void testReservationsDeniesTasksForTimePeriod() throws Exception {
    storageUtil.expectOperations();

    expectTaskStillPendingQuery(TASK_A);
    expectActiveJobFetch(TASK_A);
    expectLaunchAttempt(false);
    // Reserve "a" with offerA
    expect(preemptor.attemptPreemptionFor("a", emptyJob))
        .andReturn(Optional.of(OFFER.getOffer().getSlaveId().getValue()));

    expectTaskStillPendingQuery(TASK_B);
    expectActiveJobFetch(TASK_B);
    AssignmentCapture firstAssignment = expectLaunchAttempt(false);
    expect(preemptor.attemptPreemptionFor("b", emptyJob)).andReturn(Optional.<String>absent());

    expectTaskStillPendingQuery(TASK_B);
    expectActiveJobFetch(TASK_B);
    AssignmentCapture secondAssignment = expectLaunchAttempt(true);
    expectAssigned(TASK_B);

    control.replay();

    assertFalse(scheduler.schedule("a"));
    assertFalse(scheduler.schedule("b"));

    assignAndAssert(Result.FAILURE, GROUP_B, OFFER, firstAssignment);

    clock.advance(reservationDuration);

    assertTrue(scheduler.schedule("b"));

    assignAndAssert(Result.SUCCESS, GROUP_B, OFFER, secondAssignment);
  }

  @Test
  public void testReservationsExpireAfterAccepted() throws Exception {
    storageUtil.expectOperations();

    expectTaskStillPendingQuery(TASK_A);
    expectActiveJobFetch(TASK_A);
    expectLaunchAttempt(false);
    // Reserve "a" with offerA
    expect(preemptor.attemptPreemptionFor("a", emptyJob))
        .andReturn(Optional.of(OFFER.getOffer().getSlaveId().getValue()));

    expectTaskStillPendingQuery(TASK_A);
    expectActiveJobFetch(TASK_A);
    AssignmentCapture firstAssignment = expectLaunchAttempt(true);
    expectAssigned(TASK_A);

    expectTaskStillPendingQuery(TASK_B);
    expectActiveJobFetch(TASK_B);

    AssignmentCapture secondAssignment = expectLaunchAttempt(true);

    expect(assigner.maybeAssign(
        storageUtil.mutableStoreProvider,
        OFFER,
        new ResourceRequest(TASK_B.getAssignedTask().getTask(), Tasks.id(TASK_B), emptyJob)))
        .andReturn(Assignment.success(TaskInfo.getDefaultInstance()));

    control.replay();
    assertFalse(scheduler.schedule("a"));
    assertTrue(scheduler.schedule("a"));
    assignAndAssert(Result.SUCCESS, GROUP_A, OFFER, firstAssignment);
    eventSink.post(TaskStateChange.transition(TASK_A, PENDING));
    clock.advance(halfReservationDuration);
    assertTrue(scheduler.schedule("b"));
    assignAndAssert(Result.SUCCESS, GROUP_B, OFFER, secondAssignment);
  }

  @Test
  public void testReservationsAcceptsWithInTimePeriod() throws Exception {
    storageUtil.expectOperations();
    expectTaskStillPendingQuery(TASK_A);
    expectActiveJobFetch(TASK_A);
    expectLaunchAttempt(false);
    // Reserve "a" with offerA
    expect(preemptor.attemptPreemptionFor("a", emptyJob))
        .andReturn(Optional.of(OFFER.getOffer().getSlaveId().getValue()));

    expectTaskStillPendingQuery(TASK_A);
    expectActiveJobFetch(TASK_A);
    AssignmentCapture assignment = expectLaunchAttempt(true);
    expectAssigned(TASK_A);

    control.replay();
    assertFalse(scheduler.schedule("a"));
    clock.advance(halfReservationDuration);
    assertTrue(scheduler.schedule("a"));

    assignAndAssert(Result.SUCCESS, GROUP_A, OFFER, assignment);
  }

  @Test
  public void testReservationsCancellation() throws Exception {
    storageUtil.expectOperations();

    expectTaskStillPendingQuery(TASK_A);
    expectActiveJobFetch(TASK_A);
    expectLaunchAttempt(false);

    // Reserve "a" with offerA
    expect(preemptor.attemptPreemptionFor("a", emptyJob))
        .andReturn(Optional.of(OFFER.getOffer().getSlaveId().getValue()));

    expectTaskStillPendingQuery(TASK_B);
    expectActiveJobFetch(TASK_B);
    AssignmentCapture assignment = expectLaunchAttempt(true);
    expectAssigned(TASK_B);

    control.replay();
    assertFalse(scheduler.schedule("a"));
    clock.advance(halfReservationDuration);
    // Task is killed by user before it is scheduled
    eventSink.post(TaskStateChange.transition(TASK_A, PENDING));
    assertTrue(scheduler.schedule("b"));
    assignAndAssert(Result.SUCCESS, GROUP_B, OFFER, assignment);
  }

  @Test
  public void testReservationsExpire() throws Exception {
    storageUtil.expectOperations();

    expectTaskStillPendingQuery(TASK_B);
    expectActiveJobFetch(TASK_B);
    expectLaunchAttempt(false);
    // Reserve "b" with offer1
    expect(preemptor.attemptPreemptionFor("b", emptyJob))
        .andReturn(Optional.of(OFFER.getOffer().getSlaveId().getValue()));

    expectTaskStillPendingQuery(TASK_A);
    expectActiveJobFetch(TASK_A);
    AssignmentCapture assignment = expectLaunchAttempt(true);
    expectAssigned(TASK_A);

    control.replay();
    assertFalse(scheduler.schedule("b"));
    // We don't act on the reservation made by b because we want to see timeout behaviour.
    clock.advance(reservationDuration);
    assertTrue(scheduler.schedule("a"));
    assignAndAssert(Result.SUCCESS, GROUP_A, OFFER, assignment);
  }

  @Test
  public void testIgnoresThrottledTasks() throws Exception {
    // Ensures that tasks in THROTTLED state are not considered part of the active job state passed
    // to the assigner function.

    Storage memStorage = MemStorage.newEmptyStorage();

    Injector injector = getInjector(memStorage);
    scheduler = injector.getInstance(TaskScheduler.class);
    eventSink = PubsubTestUtil.startPubsub(injector);

    ScheduledTask builder = TASK_A.newBuilder();
    final IScheduledTask taskA = IScheduledTask.build(builder.setStatus(PENDING));
    builder.getAssignedTask().setTaskId("b");
    final IScheduledTask taskB = IScheduledTask.build(builder.setStatus(THROTTLED));

    memStorage.write(new MutateWork.NoResult.Quiet() {
      @Override
      protected void execute(MutableStoreProvider store) {
        store.getUnsafeTaskStore().saveTasks(ImmutableSet.of(taskA, taskB));
      }
    });

    AssignmentCapture assignment = expectLaunchAttempt(true);
    expect(assigner.maybeAssign(
        EasyMock.<MutableStoreProvider>anyObject(),
        eq(OFFER),
        eq(new ResourceRequest(taskA.getAssignedTask().getTask(), Tasks.id(taskA), emptyJob))))
        .andReturn(Assignment.success(TaskInfo.getDefaultInstance()));

    control.replay();

    assertTrue(scheduler.schedule(Tasks.id(taskA)));
    assignAndAssert(Result.SUCCESS, GROUP_A, OFFER, assignment);
  }

  private static IScheduledTask makeTask(String taskId) {
    return IScheduledTask.build(new ScheduledTask()
        .setAssignedTask(new AssignedTask()
            .setInstanceId(0)
            .setTaskId(taskId)
            .setTask(new TaskConfig()
                .setJob(new JobKey("role-" + taskId, "env-" + taskId, "job-" + taskId))
                .setJobName("job-" + taskId)
                .setOwner(new Identity().setRole("role-" + taskId).setUser("user-" + taskId))
                .setEnvironment("env-" + taskId))));
  }

  private static class AssignmentCapture {
    public Capture<Function<HostOffer, Assignment>> assigner = createCapture();
    public Capture<TaskGroupKey> groupKey = createCapture();
  }

  private AssignmentCapture expectLaunchAttempt(boolean taskLaunched)
      throws OfferManager.LaunchException {

    AssignmentCapture capture = new AssignmentCapture();
    expect(offerManager.launchFirst(capture(capture.assigner), capture(capture.groupKey)))
        .andReturn(taskLaunched);
    return capture;
  }

  private void assignAndAssert(
      Result result,
      TaskGroupKey groupKey,
      HostOffer offer,
      AssignmentCapture capture) {

    assertEquals(result, capture.assigner.getValue().apply(offer).getResult());
    assertEquals(groupKey, capture.groupKey.getValue());
  }

  private void expectActiveJobFetch(IScheduledTask taskInJob) {
    storageUtil.expectTaskFetch(
        TaskSchedulerImpl.activeJobStateQuery(Tasks.SCHEDULED_TO_JOB_KEY.apply(taskInJob)),
        ImmutableSet.<IScheduledTask>of());
  }
}
