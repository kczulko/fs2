package fs2

import cats._
import cats.implicits._
import cats.effect._

trait Fold[F[_], O, R] { self =>
  def fold[S](initial: S)(f: (S, O) => S)(implicit F: Sync[F]): F[(S, R)]
  def unfold: Fold[F, Nothing, Either[R, (Segment[O, Unit], Fold[F, O, R])]]

  def flatMap[R2, O2 >: O](f: R => Fold[F, O2, R2]): Fold[F, O2, R2] = new Fold[F, O2, R2] {
    def fold[S](initial: S)(g: (S, O2) => S)(implicit F: Sync[F]): F[(S, R2)] =
      self.fold(initial)(g).flatMap { case (s, r) => f(r).fold(s)(g) }
    def unfold: Fold[F, Nothing, Either[R2, (Segment[O2, Unit], Fold[F, O2, R2])]] =
      self.unfold.flatMap[Either[R2, (Segment[O2, Unit], Fold[F, O2, R2])], Nothing] {
        case Right((hd, tl)) =>
          Fold.pure[F, Nothing, Either[R2, (Segment[O2, Unit], Fold[F, O2, R2])]](
            Right((hd, tl.flatMap(f))))
        case Left(r) =>
          f(r).unfold
      }
  }

  final def >>[O2 >: O, R2 >: R](that: => Fold[F, O2, R2]): Fold[F, O2, R2] =
    flatMap(_ => that)
}

object Fold {
  def pure[F[_], O, R](r: R): Fold[F, O, R] = new Fold[F, O, R] {
    def fold[S](initial: S)(f: (S, O) => S)(implicit F: Sync[F]): F[(S, R)] =
      (initial, r).pure[F]
    def unfold: Fold[F, Nothing, Either[R, (Segment[O, Unit], Fold[F, O, R])]] =
      pure(Either.left[R, (Segment[O, Unit], Fold[F, O, R])](r))
  }

  def output1[F[_], O](o: O): Fold[F, O, Unit] = new Fold[F, O, Unit] {
    def fold[S](initial: S)(f: (S, O) => S)(implicit F: Sync[F]): F[(S, Unit)] =
      F.delay((f(initial, o), ()))
    def unfold: Fold[F, Nothing, Either[Unit, (Segment[O, Unit], Fold[F, O, Unit])]] =
      pure(
        Either
          .right[Unit, (Segment[O, Unit], Fold[F, O, Unit])](
            (Segment.singleton(o), pure[F, O, Unit](()))))
  }

  def output[F[_], O](os: Segment[O, Unit]): Fold[F, O, Unit] = new Fold[F, O, Unit] {
    def fold[S](initial: S)(f: (S, O) => S)(implicit F: Sync[F]): F[(S, Unit)] =
      F.delay((os.fold(initial)(f).force.run._2, ())) // TODO add cancelation boundary every so often
    def unfold: Fold[F, Nothing, Either[Unit, (Segment[O, Unit], Fold[F, O, Unit])]] =
      pure(Either.right[Unit, (Segment[O, Unit], Fold[F, O, Unit])]((os, pure[F, O, Unit](()))))
  }

  def eval[F[_], O, R](fr: F[R]): Fold[F, O, R] = new Fold[F, O, R] {
    def fold[S](initial: S)(f: (S, O) => S)(implicit F: Sync[F]): F[(S, R)] =
      fr.map(r => (initial, r))
    def unfold: Fold[F, Nothing, Either[R, (Segment[O, Unit], Fold[F, O, R])]] =
      eval[F, Nothing, R](fr).flatMap[Either[R, (Segment[O, Unit], Fold[F, O, R])], Nothing](r =>
        pure(Either.left[R, (Segment[O, Unit], Fold[F, O, R])](r)))
  }

  def bracket[F[_], A, O, R](acquire: F[A])(use: A => Fold[F, O, R],
                                            release: A => F[Unit]): Fold[F, O, R] =
    new Fold[F, O, R] {
      def fold[S](initial: S)(f: (S, O) => S)(implicit F: Sync[F]): F[(S, R)] =
        F.bracket(acquire)(a => use(a).fold(initial)(f))(a => release(a))
      def unfold: Fold[F, Nothing, Either[R, (Segment[O, Unit], Fold[F, O, R])]] =
        bracket[F, A, Nothing, Either[R, (Segment[O, Unit], Fold[F, O, R])]](acquire)(
          a => use(a).unfold,
          release)
    }

  implicit def monadInstance[F[_], O]: Monad[Fold[F, O, ?]] =
    new Monad[Fold[F, O, ?]] {
      def pure[A](a: A): Fold[F, O, A] = Fold.pure[F, O, A](a)
      def flatMap[A, B](p: Fold[F, O, A])(f: A => Fold[F, O, B]) = p.flatMap(f)
      def tailRecM[A, B](a: A)(f: A => Fold[F, O, Either[A, B]]) =
        f(a).flatMap {
          case Left(a)  => tailRecM(a)(f)
          case Right(b) => Fold.pure[F, O, B](b)
        }
    }
}
