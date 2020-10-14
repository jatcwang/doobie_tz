package example

import java.time.{Instant, OffsetDateTime, ZoneId}
import java.util.TimeZone

import cats.effect.{Blocker, ExitCode, IO, IOApp}
import doobie.implicits._
import doobie._

object Main extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    val xa = Transactor.fromDriverManager[IO](
      "org.postgresql.Driver", // driver classname
      "jdbc:postgresql:postgres", // connect URL (driver-specific)
      "postgres", // user
      "postgres", // password
      Blocker
        .liftExecutionContext(ExecutionContexts.synchronous), // just for testing
    )

    import doobie.implicits.legacy.instant._
    import doobie.implicits.javatime.JavaOffsetDateTimeMeta
    import doobie.hi._

    val i = Instant.parse("2018-01-01T00:00:00.000Z")

    val zone = ZoneId.systemDefault()
    println(s"JVM timezone: $zone")

    val offsetDt = OffsetDateTime.ofInstant(i, zone)

    (for {
      _ <- fr"""show timezone"""
        .query[String]
        .unique
        .map(z => println(s"DB Connection timezone: $z"))
      _ <- fr"""
          CREATE TEMPORARY TABLE ttt (
            idx INTEGER PRIMARY KEY,
            ts TIMESTAMP NOT NULL,
            tsz TIMESTAMPTZ NOT NULL
          )
          """.update.run
      _ <- fr"""
          INSERT INTO ttt (idx, ts, tsz) VALUES (0, $i, $i)
          """.update.run
      _ <- fr"""
          INSERT INTO ttt (idx, ts, tsz) VALUES (1, $offsetDt, $offsetDt)
             """.update.run
      _ = println("=== values inserted into DB ===")

      // Set the JVM to a different timezone, simulating e.g. a different machine or DST
      _ = {
        println("Setting JVM timezone to EST")
        TimeZone.setDefault(TimeZone.getTimeZone("EST"))
      }

      _ <- fr"""SELECT ts, tsz FROM ttt where idx = 0"""
        .query[(Instant, Instant)]
        .unique
        .map {
          case (ts, tsz) =>
            println("== Instant ==")
            println(s"Original: $i")
            println(s"ts: $ts")
            println(s"tsz: $tsz")
        }

      _ <- fr"""SELECT ts, tsz FROM ttt where idx = 1"""
        .query[(OffsetDateTime, OffsetDateTime)]
        .unique
        .map {
          case (ts, tsz) =>
            println("== OffsetDateTime ==")
            println(s"Original: $offsetDt")
            println(s"ts: $ts") // wrong instant
            println(s"tsz: $tsz") // Note that the "instant" is still correct, but the offset gets set to +0. This is because TIMEZONETZ doesn't actually store timezones
        }

    } yield {
      ExitCode.Success
    }).transact(xa)

  }
}
