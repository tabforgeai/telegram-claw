package ai.tabforge.telegramclaw.relay;

/**
 * Thrown when CommandDispatcher fails to deliver a ClawCommand to the Android device via FCM.
 *
 * <p>Analogy: like a courier company's delivery failure notice — the package (command) was
 * prepared and handed to the courier (Firebase), but something went wrong before it reached
 * the recipient's door (the Android device). The failure could be a bad address (invalid
 * device token), a network outage, or an authentication problem with Firebase itself.</p>
 *
 * <p>Thrown by: {@link CommandDispatcher#send(ai.tabforge.telegramclaw.relay.model.ClawCommand)}.
 * Caught by: {@link TelegramUpdateReceiver#handleMessage}, which logs the failure and continues.</p>
 */
public class DispatchException extends Exception {

    /**
     * Constructs a DispatchException with a descriptive message.
     *
     * @param message  explanation of what failed and why
     */
    public DispatchException(String message) {
        super(message);
    }

    /**
     * Constructs a DispatchException wrapping a lower-level cause.
     *
     * @param message  explanation of what failed and why
     * @param cause    the underlying exception from Firebase or the network layer
     */
    public DispatchException(String message, Throwable cause) {
        super(message, cause);
    }
}
