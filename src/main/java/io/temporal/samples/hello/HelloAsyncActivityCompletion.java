/*
 *  Copyright (c) 2020 Temporal Technologies, Inc. All Rights Reserved
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.temporal.samples.hello;

import io.temporal.activity.*;
import io.temporal.client.ActivityCompletionClient;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;

/**
 * Sample Temporal workflow that demonstrates asynchronous activity implementation.
 *
 * <p>To execute this example a locally running Temporal service instance is required. You can
 * follow instructions on how to set up your Temporal service here:
 * https://github.com/temporalio/temporal/blob/master/README.md#download-and-start-temporal-server-locally
 */
public class HelloAsyncActivityCompletion {

  // Define the task queue name
  static final String TASK_QUEUE = "HelloAsyncActivityCompletionTaskQueue";

  // Define the workflow unique id
  static final String WORKFLOW_ID = "HelloAsyncActivityCompletionWorkflow";

  /**
   * Define the Workflow Interface. It must contain one method annotated with @WorkflowMethod.
   *
   * <p>Workflow code includes core processing logic. It that shouldn't contain any heavyweight
   * computations, non-deterministic code, network calls, database operations, etc. All those things
   * should be handled by Activities.
   *
   * @see io.temporal.workflow.WorkflowInterface
   * @see io.temporal.workflow.WorkflowMethod
   */
  @WorkflowInterface
  public interface GreetingWorkflow {

    /**
     * This method is executed when the workflow is started. The workflow completes when the
     * workflow method finishes execution.
     */
    @WorkflowMethod
    String getGreeting(String name);
  }

  /**
   * Define the Activity Interface. Activities are building blocks of any temporal workflow and
   * contain any business logic that could perform long running computation, network calls, etc.
   *
   * <p>Annotating activity methods with @ActivityMethod is optional
   *
   * @see io.temporal.activity.ActivityInterface
   * @see io.temporal.activity.ActivityMethod
   */
  @ActivityInterface
  public interface GreetingActivities {

    /** Define the activity method which can be called during workflow execution */
    String composeGreeting(String greeting, String name);
  }

  // Define the workflow implementation which implements the getGreeting workflow method.
  public static class GreetingWorkflowImpl implements GreetingWorkflow {

    /**
     * Define the GreetingActivities stub. Activity stubs are proxies for activity invocations that
     * are executed outside of the workflow thread on the activity worker, that can be on a
     * different host. Temporal is going to dispatch the activity results back to the workflow and
     * unblock the stub as soon as activity is completed on the activity worker.
     *
     * <p>Let's take a look at each {@link ActivityOptions} defined:
     *
     * <p>The "setScheduleToCloseTimeout" option sets the overall timeout that the workflow is
     * willing to wait for activity to complete. For this example it is set to 10 seconds.
     */
    private final GreetingActivities activities =
        Workflow.newActivityStub(
            GreetingActivities.class,
            ActivityOptions.newBuilder().setScheduleToCloseTimeout(Duration.ofSeconds(10)).build());

    @Override
    public String getGreeting(String name) {
      // This is a blocking call that returns only after the activity has completed.
      return activities.composeGreeting("Hello", name);
    }
  }

  /**
   * Implementation of our workflow activity interface. It overwrites the defined composeGreeting
   * activity method.
   */
  static class GreetingActivitiesImpl implements GreetingActivities {

    /**
     * ActivityCompletionClient is used to asynchronously complete activities. In this example we
     * will use this client alongside with {@link
     * io.temporal.activity.ActivityExecutionContext#doNotCompleteOnReturn()} which means our
     * activity method will not complete when it returns, however is expected to be completed
     * asynchronously using the client.
     */
    private final ActivityCompletionClient completionClient;

    GreetingActivitiesImpl(ActivityCompletionClient completionClient) {
      this.completionClient = completionClient;
    }

    @Override
    public String composeGreeting(String greeting, String name) {

      // Get the activity execution context
      ActivityExecutionContext context = Activity.getExecutionContext();

      // Set a correlation token that can be used to complete the activity asynchronously
      byte[] taskToken = context.getTaskToken();

      /*
       * For the example we will use a {@link java.util.concurrent.ForkJoinPool} to execute our
       * activity. In real-life applications this could be any service. The composeGreetingAsync
       * method is the one that will actually complete workflow action execution.
       */
      ForkJoinPool.commonPool().execute(() -> composeGreetingAsync(taskToken, greeting, name));
      context.doNotCompleteOnReturn();

      // Since we have set doNotCompleteOnReturn(), the workflow action method return value is
      // ignored.
      return "ignored";
    }

    // Method that will complete action execution using the defined ActivityCompletionClient
    private void composeGreetingAsync(byte[] taskToken, String greeting, String name) {
      String result = greeting + " " + name + "!";

      // Complete our workflow activity using ActivityCompletionClient
      completionClient.complete(taskToken, result);
    }
  }

  /**
   * With our Workflow and Activities defined, we can now start execution. The main method starts
   * the worker and then the workflow.
   */
  public static void main(String[] args) throws ExecutionException, InterruptedException {

    // Define the workflow service.
    WorkflowServiceStubs service = WorkflowServiceStubs.newInstance();

    /*
     * Define the workflow client. It is a Temporal service client used to start, signal, and query
     * workflows
     */
    WorkflowClient client = WorkflowClient.newInstance(service);

    /*
     * Define the workflow factory. It is used to create workflow workers for a specific task queue.
     */
    WorkerFactory factory = WorkerFactory.newInstance(client);

    /*
     * Define the workflow worker. Workflow workers listen to a defined task queue and process
     * workflows and activities.
     */
    Worker worker = factory.newWorker(TASK_QUEUE);

    /*
     * Register our workflow implementation with the worker.
     * Workflow implementations must be known to the worker at runtime in
     * order to dispatch workflow tasks.
     */
    worker.registerWorkflowImplementationTypes(GreetingWorkflowImpl.class);

    /*
     * Register our workflow activity implementation with the worker. Since workflow activities are
     * stateless and thread-safe, we need to register a shared instance. We create our
     * ActivityCompletionClient and pass it to the workflow activity implementation
     */
    ActivityCompletionClient completionClient = client.newActivityCompletionClient();
    worker.registerActivitiesImplementations(new GreetingActivitiesImpl(completionClient));

    /*
     * Start all the workers registered for a specific task queue.
     * The started workers then start polling for workflows and activities.
     */
    factory.start();

    // Create the workflow client stub. It is used to start our workflow execution.
    GreetingWorkflow workflow =
        client.newWorkflowStub(
            GreetingWorkflow.class,
            WorkflowOptions.newBuilder()
                .setWorkflowId(WORKFLOW_ID)
                .setTaskQueue(TASK_QUEUE)
                .build());

    /*
     * Here we use {@link io.temporal.client.WorkflowClient} to execute our workflow asynchronously.
     * It gives us back a {@link java.util.concurrent.CompletableFuture}. We can then call its get
     * method to block and wait until a result is available.
     */
    CompletableFuture<String> greeting = WorkflowClient.execute(workflow::getGreeting, "World");

    // Wait for workflow execution to complete and display its results.
    System.out.println(greeting.get());
    System.exit(0);
  }
}
