package helloworldapp;

public interface Shared {

    // Define the task queue name
    final String HELLO_WORLD_TASK_QUEUE = "HelloWorldTaskQueue";

    // Task queue for the durability demo (see helloworldapp.durability)
    final String DURABLE_ORDER_TASK_QUEUE = "DurableOrderTaskQueue";

}