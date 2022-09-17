package org.broadinstitute.dsm.util.tryimpl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Try class is an abstraction for try/catch procedures.
 * Try class suggests a few methods for handling some common situations
 * associated with try/catch procedures.
 * These situations can be:
 *  - folding the computation with success function or default value
 *  - finalizing computation with Runnables
 *  - catching user-specified exception and returning default value
 *  - catching user-specified exception and running some task afterwards
 */
public abstract class Try<T> {

    /**
     * Some Try computations may start with calling Try.evaluate([some Callable here]).
     * Returns either Success or Failure instance based on computation
     * @param callable a lazy computation which is not evaluated on the call site, instead it's evaluated here.
     */
    public static <T> Try<T> evaluate(Callable<? extends T> callable) {
        try {
            return new Success<>(Optional.of(callable.call()));
        } catch (Exception e) {
            return (Try<T>) new Failure(e);
        }
    }

    /**
     * Some Try computations may start with calling Try.evaluate([some Runnable here]).
     * Returns either Success or Failure instance based on computation
     * @param runnable a lazy task which is not evaluated on the call site, instead it's evaluated here.
     *
     */
    public static <T> Try<T> evaluate(Runnable runnable) {
        try {
            runnable.run();
            return new Success<>(Optional.empty());
        } catch (Exception e) {
            return (Try<T>) new Failure(e);
        }
    }

    /**
     * Some Try computations may start with calling Try.evaluate([some Consumer here][some argument here]).
     * Returns either Success or Failure instance based on computation
     * @param consumer a lazy single-param function value which consumes argument for side effects.
     *
     */
    public static <T> Try<T> evaluate(T arg, Consumer<T> consumer) {
        try {
            consumer.accept(arg);
            return new Success<>(Optional.empty());
        } catch (Exception e) {
            return (Try<T>) new Failure(e);
        }
    }

    /**
     * Some Try computations may start with calling Try.evaluate([some Function here][some argument here]).
     * Returns either Success or Failure instance based on computation
     * @param function a function value which consumes argument and may succeed or fail. It's not evaluated on the call site.
     *
     */
    public static <T, V> Try<V> evaluate(T arg, Function<T, ? extends V> function) {
        try {
            return new Success<>(Optional.of(function.apply(arg)));
        } catch (Exception e) {
            return (Try<V>) new Failure(e);
        }
    }


    /**
     * Returns the value based on if the main computation succeeded or failed
     * @param successMapper a function which can transform the successful value, or leave it as it is (Function.identity())
     * @param defaultValue  a default value which will be returned if the computation fails
     */
    public final <V> V fold(Function<? super T, ? extends V> successMapper, V defaultValue) {
        if (this instanceof Success) {
            Success<T> success = (Success<T>) this;
            return successMapper.apply(success.getValue());
        } else {
            return defaultValue;
        }
    }

    /**
     * Runs the tasks based on if the main computation succeeded or failed
     * @param onSuccess a runnable which will be executed in case of successful computation
     * @param onFailure a runnable which will be executed in case of failed computation
     */
    public final void finalizeWith(Runnable onSuccess, Runnable onFailure) {
        if (this instanceof Success) {
            onSuccess.run();
        } else {
            onFailure.run();
        }
    }

    /**
     * Returns the default value if the evaluation procedure will catch the user-specified exception
     * otherwise returns the successive value
     * @param successMapper a function for transforming success further, or else returning self by using Function.identity()
     * @param defaultValue  a default value to be returned in case of user-specified failure
     * @param exceptions    a user specified exception array some of which can be caught
     */

    @SafeVarargs
    public final <V> V ifThrowsThenGetDefaultOrElseMap(
            Function<? super T, ? extends V> successMapper,
            V defaultValue, Class<? extends Exception>... exceptions
    ) {
        if (this instanceof Failure) {
            Failure failure = (Failure) this;
            Class<? extends Exception> currentException       = failure.getValue().getClass();
            List<Class<? extends Exception>> parentExceptions = TryUtils.getParentExceptions(failure.getValue());
            parentExceptions.add(currentException);
            if (Arrays.stream(exceptions).anyMatch(exception -> parentExceptions.stream().anyMatch(exception::equals))) {
                return defaultValue;
            } else {
                throw new UncaughtException();
            }
        } else {
            Success<T> success = (Success<T>) this;
            return successMapper.apply(success.getValue());
        }
    }

    /**
     * Returns the successful computation in case of success, or else runs the consumer of user-specified exception
     * @param consumer   a consumer of user-specified exception which may be caught after evaluation
     * @param exceptions a user-defined array of possible exceptions
     */

    @SafeVarargs
    public final T ifThrowsThenRunTaskElseGet(Consumer<? super Exception> consumer, Class<? extends Exception>... exceptions) {
        if (this instanceof Failure) {
            Failure failure = (Failure) this;
            Class<? extends Exception> currentException       = failure.getValue().getClass();
            List<Class<? extends Exception>> parentExceptions = TryUtils.getParentExceptions(failure.getValue());
            parentExceptions.add(currentException);
            if (Arrays.stream(exceptions).anyMatch(exception -> parentExceptions.stream().anyMatch(exception::equals))) {
                consumer.accept(failure.getValue());
                return null;
            } else {
                throw new UncaughtException();
            }
        } else {
            Success<T> success = (Success<T>) this;
            return success.getValue();
        }
    }

    /**
     * Runs the user specified task if the evaluation procedure will catch
     * at least one of the user-specified exceptions otherwise it won't run any tasks
     * @param consumer  an exception consumer for running the task
     * @param exceptions a user specified exceptions which can be caught
     */
    @SafeVarargs
    public final void ifThrowsCatchAndThenRun(Consumer<? super Exception> consumer, Class<? extends Exception>... exceptions) {
        if (this instanceof Failure) {
            Failure failure = (Failure) this;
            Class<? extends Exception> currentException       = failure.getValue().getClass();
            List<Class<? extends Exception>> parentExceptions = TryUtils.getParentExceptions(failure.getValue());
            parentExceptions.add(currentException);
            if (Arrays.stream(exceptions).anyMatch(exception -> parentExceptions.stream().anyMatch(exception::equals))) {
                consumer.accept(failure.getValue());
            }
        }
    }

    /**
     * abstract method which must be implemented by the children of org.ghurtchu.impl.Try
     */
    protected abstract T getValue();

    /**
     * A success case which encapsulates the successful computation
     */
    private static final class Success<T> extends Try<T> {

        private final Optional<T> value;

        public Success(Optional<T> value) {
            this.value = value;
        }

        @Override
        protected T getValue() {
            return value.get();
        }
    }

    /**
     * A failed case which ensapsulates the failed computation
     */
    private static final class Failure extends Try<Exception> {

        private final Exception exception;

        public Failure(Exception exception) {
            this.exception = exception;
        }

        @Override
        protected Exception getValue() {
            return exception;
        }

    }

    /**
     * A special exception for indicating that user could not catch it
     */
    public static final class UncaughtException extends RuntimeException {

        @Override
        public String getMessage() {
            return "UncaughtException";
        }
    }

    private static class TryUtils {

        public static List<Class<? extends Exception>> getParentExceptions(Exception exception) {
            List<Class<? extends Exception>> superExceptions = new ArrayList<>();
            Class<?> superExc                                = exception.getClass().getSuperclass();
            while (true) {
                if (superExc == null) {
                    break;
                } else {
                    superExceptions.add((Class<? extends Exception>) superExc);
                    superExc = superExc.getSuperclass();
                }
            }
            return superExceptions;
        }

    }


}