/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal

import scala.util.control.Exception.Catcher

import akka.actor.typed
import akka.actor.typed.Behavior.UnstashingBehavior
import akka.actor.typed.Behavior.{ SameBehavior, UnhandledBehavior }
import akka.actor.typed.internal.TimerSchedulerImpl.TimerMsg
import akka.actor.typed.{ LogOptions, _ }
import akka.annotation.InternalApi
import akka.util.LineNumbers
import scala.util.control.NonFatal

/**
 * Provides the impl of any behavior that could nest another behavior
 *
 * INTERNAL API
 */
@InternalApi
private[akka] object InterceptorImpl {

  def apply[O, I](interceptor: BehaviorInterceptor[O, I], nestedBehavior: Behavior[I]): Behavior[O] = {
    Behavior.DeferredBehavior[O] { ctx ⇒
      val interceptorBehavior = new InterceptorImpl[O, I](interceptor, nestedBehavior)
      interceptorBehavior.preStart(ctx)
    }
  }
}

/**
 * Provides the impl of any behavior that could nest another behavior
 *
 * INTERNAL API
 */
@InternalApi
private[akka] final class InterceptorImpl[O, I](val interceptor: BehaviorInterceptor[O, I], private var _nestedBehavior: Behavior[I])
  extends ExtensibleBehavior[O] with WrappingBehavior[O, I] {

  override def nestedBehavior: Behavior[I] = _nestedBehavior

  import BehaviorInterceptor._

  private val preStartTarget: PreStartTarget[I] = new PreStartTarget[I] {
    override def start(ctx: TypedActorContext[_]): Behavior[I] = {
      Behavior.start[I](nestedBehavior, ctx.asInstanceOf[TypedActorContext[I]])
    }
  }

  private val receiveTarget: ReceiveTarget[I] = new ReceiveTarget[I] {
    override def apply(ctx: TypedActorContext[_], msg: I): Behavior[I] = {
      try {
        val ctxI = ctx.asInstanceOf[TypedActorContext[I]]
        val nextB = Behavior.interpretMessage(nestedBehavior, ctxI, msg)
        next(ctxI, nextB)
      } catch catchFromUnstashing
    }

    override def signalRestart(ctx: TypedActorContext[_]): Unit =
      Behavior.interpretSignal(nestedBehavior, ctx.asInstanceOf[TypedActorContext[I]], PreRestart)
  }

  private val signalTarget = new SignalTarget[I] {
    override def apply(ctx: TypedActorContext[_], signal: Signal): Behavior[I] = {
      try {
        val ctxI = ctx.asInstanceOf[TypedActorContext[I]]
        val nextB = Behavior.interpretSignal(nestedBehavior, ctxI, signal)
        next(ctxI, nextB)
      } catch catchFromUnstashing
    }
  }

  private val catchFromUnstashing: Catcher[Behavior[I]] = {
    case NonFatal(e) ⇒
      println(s"# interceptor catchFromUnstashing [${e.getMessage}] nestedBehavior [$nestedBehavior]") // FIXME
      // unstashing is aborted on failure
      nestedBehavior match {
        case u: UnstashingBehavior[I] ⇒
          _nestedBehavior = u.currentBehavior
        case _ ⇒
      }
      throw e
  }

  // invoked pre-start to start/de-duplicate the initial behavior stack
  def preStart(ctx: typed.TypedActorContext[O]): Behavior[O] = {
    val started = interceptor.aroundStart(ctx, preStartTarget)
    deduplicate(started, ctx)
  }

  override def replaceNested(newNested: Behavior[I]): Behavior[O] =
    new InterceptorImpl(interceptor, newNested)

  override def receive(ctx: typed.TypedActorContext[O], msg: O): Behavior[O] = {
    val interceptMessageType = interceptor.interceptMessageType
    val result =
      if (interceptMessageType == null || interceptMessageType.isAssignableFrom(msg.getClass))
        interceptor.aroundReceive(ctx, msg, receiveTarget)
      else
        receiveTarget.apply(ctx, msg.asInstanceOf[I])
    deduplicate(result, ctx)
  }

  override def receiveSignal(ctx: typed.TypedActorContext[O], signal: Signal): Behavior[O] = {
    val interceptedResult = interceptor.aroundSignal(ctx, signal, signalTarget)
    deduplicate(interceptedResult, ctx)
  }

  // invoked from the interceptor targets, so that unstashing happens when the interceptor
  // passes the message/signal to it
  private def next(ctx: TypedActorContext[I], b: Behavior[I]): Behavior[I] =
    b match {
      case u: UnstashingBehavior[I] ⇒
        // keep the unstashing behavior as current, so that intermediate failures while unstashing
        // can be handled correctly
        val previousBehavior = nestedBehavior
        _nestedBehavior = u
        _nestedBehavior = u.unstash(previousBehavior, ctx)
        _nestedBehavior

      case other ⇒
        Behavior.canonicalize(other, other, ctx)
    }

  private def deduplicate(interceptedResult: Behavior[I], ctx: TypedActorContext[O]): Behavior[O] = {
    val started = Behavior.start(interceptedResult, ctx.asInstanceOf[TypedActorContext[I]])
    if (started == UnhandledBehavior || started == SameBehavior || !Behavior.isAlive(started)) {
      started.unsafeCast[O]
    } else {
      // returned behavior could be nested in setups, so we need to start before we deduplicate
      val duplicateInterceptExists = Behavior.existsInStack(started) {
        case i: InterceptorImpl[O, I] if interceptor.isSame(i.interceptor.asInstanceOf[BehaviorInterceptor[Any, Any]]) ⇒ true
        case _ ⇒ false
      }

      if (duplicateInterceptExists) started.unsafeCast[O]
      else new InterceptorImpl[O, I](interceptor, started)
    }
  }

  override def toString(): String = s"Interceptor($interceptor, $nestedBehavior)"
}

/**
 * Fire off any incoming message to another actor before receiving it ourselves.
 *
 * INTERNAL API
 */
@InternalApi
private[akka] final case class MonitorInterceptor[T](actorRef: ActorRef[T]) extends BehaviorInterceptor[T, T] {
  import BehaviorInterceptor._

  override def aroundReceive(ctx: TypedActorContext[T], msg: T, target: ReceiveTarget[T]): Behavior[T] = {
    actorRef ! msg
    target(ctx, msg)
  }

  override def aroundSignal(ctx: TypedActorContext[T], signal: Signal, target: SignalTarget[T]): Behavior[T] = {
    target(ctx, signal)
  }

  // only once to the same actor in the same behavior stack
  override def isSame(other: BehaviorInterceptor[Any, Any]): Boolean = other match {
    case MonitorInterceptor(`actorRef`) ⇒ true
    case _                              ⇒ false
  }

}

/**
 * Log all messages for this decorated ReceiveTarget[T] to logger before receiving it ourselves.
 *
 * INTERNAL API
 */
@InternalApi
private[akka] final case class LogMessagesInterceptor[T](opts: LogOptions) extends BehaviorInterceptor[T, T] {

  import BehaviorInterceptor._

  override def aroundReceive(ctx: TypedActorContext[T], msg: T, target: ReceiveTarget[T]): Behavior[T] = {
    if (opts.enabled)
      opts.logger.getOrElse(ctx.asScala.log).log(opts.level, "received message {}", msg)
    target(ctx, msg)
  }

  override def aroundSignal(ctx: TypedActorContext[T], signal: Signal, target: SignalTarget[T]): Behavior[T] = {
    if (opts.enabled)
      opts.logger.getOrElse(ctx.asScala.log).log(opts.level, "received signal {}", signal)
    target(ctx, signal)
  }

  // only once in the same behavior stack
  override def isSame(other: BehaviorInterceptor[Any, Any]): Boolean = other match {
    case LogMessagesInterceptor(`opts`) ⇒ true
    case _                              ⇒ false
  }
}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object WidenedInterceptor {

  private final val _any2null = (_: Any) ⇒ null
  private final def any2null[T] = _any2null.asInstanceOf[Any ⇒ T]
}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] final case class WidenedInterceptor[O, I](matcher: PartialFunction[O, I]) extends BehaviorInterceptor[O, I] {
  import WidenedInterceptor._
  import BehaviorInterceptor._

  override def isSame(other: BehaviorInterceptor[Any, Any]): Boolean = other match {
    // If they use the same pf instance we can allow it, to have one way to workaround defining
    // "recursive" narrowed behaviors.
    case WidenedInterceptor(`matcher`) ⇒ true
    case WidenedInterceptor(otherMatcher) ⇒
      // there is no safe way to allow this
      throw new IllegalStateException("Widen can only be used one time in the same behavior stack. " +
        s"One defined in ${LineNumbers(matcher)}, and another in ${LineNumbers(otherMatcher)}")
    case _ ⇒ false
  }

  def aroundReceive(ctx: TypedActorContext[O], msg: O, target: ReceiveTarget[I]): Behavior[I] = {
    // widen would wrap the TimerMessage, which would be wrong, see issue #25318
    msg match {
      case t: TimerMsg ⇒ throw new IllegalArgumentException(
        s"Timers and widen can't be used together, [${t.key}]. See issue #25318")
      case _ ⇒ ()
    }

    matcher.applyOrElse(msg, any2null) match {
      case null        ⇒ Behavior.unhandled
      case transformed ⇒ target(ctx, transformed)
    }
  }

  def aroundSignal(ctx: TypedActorContext[O], signal: Signal, target: SignalTarget[I]): Behavior[I] =
    target(ctx, signal)

  override def toString: String = s"Widen(${LineNumbers(matcher)})"
}
