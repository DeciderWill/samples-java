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

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;

/**
 * Sample Temporal workflow that demonstrates the use of multiple activities which extend a common
 * interface.
 *
 * <p>To execute this example a locally running Temporal service instance is required. You can
 * follow instructions on how to set up your Temporal service here:
 * https://github.com/temporalio/temporal/blob/master/README.md#download-and-start-temporal-server-locally
 */
public class HelloPolymorphicActivity {

  // Define the task queue name
  static final String TASK_QUEUE = "HelloPolymorphicActivityTaskQueue";

  // Define the workflow unique id
  static final String WORKFLOW_ID = "HelloPolymorphicActivityWorkflow";

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

  // Define the base interface for the two workflow activities
  // Note it is not annotated with @ActivityInterface
  public interface GreetingActivity {
    String composeGreeting(String name);
  }

  /**
   * Define the first activity interface. Workflow methods can call activities during execution.
   * Annotating activity methods with @ActivityMethod is optional
   *
   * <p>Note the activity interface extends the base GreetingActivity interface. Also note that in
   * order to void the collisions in the activity name (which is by default the name of the activity
   * method) we set the namePrefix annotation parameter.
   *
   * @see io.temporal.activity.ActivityInterface
   * @see io.temporal.activity.ActivityMethod
   */
  @ActivityInterface(namePrefix = "Hello_")
  public interface HelloActivity extends GreetingActivity {
    @Override
    String composeGreeting(String name);
  }

  /**
   * Define the second activity interface. Workflow methods can call activities during execution.
   * Annotating activity methods with @ActivityMethod is optional
   *
   * <p>Note that the activity interface extends the base GreetingActivity interface. Also note that
   * in order to void the collisions in the activity name (which is by default the name of the
   * activity method) we set the namePrefix annotation parameter.
   *
   * @see io.temporal.activity.ActivityInterface
   * @see io.temporal.activity.ActivityMethod
   */
  @ActivityInterface(namePrefix = "Bye_")
  public interface ByeActivity extends GreetingActivity {
    @Override
    String composeGreeting(String name);
  }

  // Define the workflow implementation which implements the getGreeting workflow method.
  public static class GreetingWorkflowImpl implements GreetingWorkflow {

    /**
     * Define the GreetingActivities stub. Activity stubs are proxies for activity invocations that
     * are executed outside of the workflow thread on the activity worker, that can be on a
     * different host. Temporal is going to dispatch the activity results back to the workflow and
     * unblock the stub as soon as activity is completed on the activity worker.
     *
     * <p>For this example we define two activity stubs, one for each of the defined activities.
     *
     * <p>Let's take a look at each {@link ActivityOptions} defined: The "setScheduleToCloseTimeout"
     * option sets the overall timeout that the workflow is willing to wait for activity to
     * complete. For this example it is set to 2 seconds for each of the defined activities.
     */
    private final GreetingActivity[] activities =
        new GreetingActivity[] {
          Workflow.newActivityStub(
              HelloActivity.class,
              ActivityOptions.newBuilder()
                  .setScheduleToCloseTimeout(Duration.ofSeconds(2))
                  .build()),
          Workflow.newActivityStub(
              ByeActivity.class,
              ActivityOptions.newBuilder().setScheduleToCloseTimeout(Duration.ofSeconds(2)).build())
        };

    @Override
    public String getGreeting(String name) {
      StringBuilder result = new StringBuilder();

      /*
       * Call the composeGreeting activity method
       * for each of our two activities.
       * Notice how you can use the common activities interface for each.
       *
       * Append the result of each of the activity invocation results and return it.
       */
      for (GreetingActivity activity : activities) {
        result.append(activity.composeGreeting(name));
        result.append('\n');
      }
      return result.toString();
    }
  }

  // Hello workflow activity implementation
  static class HelloActivityImpl implements HelloActivity {
    @Override
    public String composeGreeting(String name) {
      return "Hello " + name + "!";
    }
  }

  // Bye workflow activity implementation
  static class ByeActivityImpl implements ByeActivity {
    @Override
    public String composeGreeting(String name) {
      return "Bye " + name + "!";
    }
  }

  /**
   * With our Workflow and Activities defined, we can now start execution. The main method starts
   * the worker and then the workflow.
   */
  public static void main(String[] args) {

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
     Register our workflow activities implementations with the worker. Since workflow activities are
     stateless and thread-safe, we need to register a shared instance.
    */
    worker.registerActivitiesImplementations(new HelloActivityImpl(), new ByeActivityImpl());

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
     * Execute our workflow and wait for it to complete. The call to our getGreeting method is
     * synchronous.
     *
     * See {@link io.temporal.samples.hello.HelloSignal} for an example of starting workflow
     * without waiting synchronously for its result.
     */
    String greeting = workflow.getGreeting("World");

    // Print the workflow results. It should contain the results
    // of both of our defined activities
    System.out.println(greeting);
    System.exit(0);
  }
}
