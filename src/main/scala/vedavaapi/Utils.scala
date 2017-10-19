package vedavaapi

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object Utils {
  def mapValuesToTrys[T](futures: Seq[Future[T]])(implicit ec: ExecutionContext): Seq[Future[Try[T]]] =
    futures.map(_.map { Success(_) }.recover { case t => Failure(t) })
  def getFutureOfTrys[T](futures: Seq[Future[T]])(implicit ec: ExecutionContext): Future[Seq[Try[T]]] =
    Future.sequence(mapValuesToTrys(futures = futures))

}
