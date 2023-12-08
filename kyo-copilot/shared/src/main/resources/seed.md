Kyo is a Scala development toolkit for both browser-based and backend applications, offering a user-friendly approach to functional programming without complex theory. It expands on concepts from ZIO, allowing more flexibility with multiple effectful channels, simplifying its internal structure and enhancing developer experience.

### Getting Started

```scala 
libraryDependencies += "io.getkyo" %% "kyo-core" % "<version>"
libraryDependencies += "io.getkyo" %% "kyo-direct" % "<version>"
libraryDependencies += "io.getkyo" %% "kyo-cache" % "<version>"
libraryDependencies += "io.getkyo" %% "kyo-stats-otel" % "<version>"
libraryDependencies += "io.getkyo" %% "kyo-sttp" % "<version>"
```

### The `>` type

```scala 
import kyo._

// Expect an 'Int' after handling 
// the 'Options' effect
Int > Options

// Expect a 'String' after handling 
// both 'Options' and 'IOs' effects
String > (Options with IOs)

// An 'Int' is also an 'Int > Any'
val a: Int > Any = 1

// Since there are no pending effects, 
// the computation can produce a pure value
val b: Int = a.pure

import kyo.options._
import kyo.tries._

// Kyo still supports both `map` and `flatMap`
def example1(a: Int > Options, b: Int > Tries): Int > (Options with Tries) =
  a.flatMap(v => b.map(_ + v))

// but using only `map` is recommended 
def example2(a: Int > Options, b: Int > Tries): Int > (Options with Tries) =
  a.map(v => b.map(_ + v))
```

### Effect widening

```scala
// An 'Int' with an empty effect set (`Any`)
val a: Int > Any = 
  1

// Widening the effect set from empty (`Any`) 
// to include `Options`
val b: Int > Options = 
  a

// Further widening the effect set to include 
// both `Options` and `Tries`
val c: Int > (Options with Tries) = 
  b

// Directly widening a pure value to have 
// `Options` and `Tries`
val d: Int > (Options with Tries) = 
  42

// The function expects a parameter with both 
// 'Options' and 'Tries' effects pending
def example1(v: Int > (Options with Tries)) = 
  v.map(_ + 1)

// A value with only the 'Tries' effect can be 
// automatically widened to include 'Options'
def example2(v: Int > Tries) = 
  example1(v)

// A pure value can also be automatically widened
def example3 = example1(42)
```

### Using effects

```scala
val a: Int > Options = 42

// Handle the 'Options' effect
val b: Option[Int] > Any = 
  Options.run(a)

// Retrieve pure value as there are no more pending effects
val c: Option[Int] = 
  b.pure

val ex = new Exception

// If the effects don't short-circuit, only the 
// order of nested types in the result changes
assert(optionsFirst(Options.get(Some(1))) == Success(Some(1)))
assert(optionsFirst(Tries.get(Success(1))) == Success(Some(1)))

// Note how the result type changes from 
// 'Try[Option[T]]' to 'Option[Try[T]]'
assert(triesFirst(Options.get(Some(1))) == Some(Success(1)))
assert(triesFirst(Tries.get(Success(1))) == Some(Success(1)))

// If there's short-circuiting, the 
// resulting value can be different
assert(optionsFirst(Options.get(None)) == Success(None))
assert(optionsFirst(Tries.get(Failure(ex))) == Failure(ex))

assert(triesFirst(Options.get(None)) == None)
assert(triesFirst(Tries.get(Failure(ex))) == Some(Failure(ex)))
```

### Direct Syntax

```scala
import kyo.direct._
import scala.util.Try

// Use the direct syntax
val a: String > (Tries with Options) =
  defer {
    val b: String = 
      await(Options.get(Some("hello")))
    val c: String = 
      await(Tries.get(Try("world")))
    b + " " + c
  }

// Equivalent desugared
val b: String > (Tries with Options) =
  Options.get(Some("hello")).map { b =>
    Tries.get(Try("world")).map { c =>
      b + " " + c
    }
  }

import kyo.ios._

// This code fails to compile
val a: Int > (IOs with Options) =
  defer {
    // Incorrect usage of a '>' value 
    // without 'await' 
    IOs(println(42))
    val c: Int = 
      await(Options.get(Some(1)))
    c + 10
  }

import kyo.direct._
import kyo.ios._

defer {
  // Pure expression
  val a: Int = 5
  
  // Effectful value
  val b: Int = await(IOs(10))
  
  // Control flow
  val c: String = 
    if (await(IOs(true))) "True branch" else "False branch"
  
  // Logical operations
  val d: Boolean = 
    await(IOs(true)) && await(IOs(false))
  
  val e: Boolean = 
    await(IOs(true)) || await(IOs(true))
  
  // Loop (for demonstration; this loop 
  // won't execute its body)
  while (await(IOs(false))) { "Looping" }
  
  // Pattern matching
  val matchResult: String = 
    await(IOs(1)) match {
      case 1 => "One"
      case _ => "Other"
    }
}
```

### Defining an App

```scala
import kyo.clocks._
import kyo.consoles._
import kyo.randoms._

object MyApp extends App {
  def run(args: List[String]) = 
    for {
      _ <- Consoles.println("Starting the app...")
      currentTime <- Clocks.now
      _ <- Consoles.println(s"Current time is: $currentTime")
      randomNumber <- Randoms.nextInt(100)
      _ <- Consoles.println(s"Generated random number: $randomNumber")
    } yield ()
}

import kyo.concurrent.fibers._
import scala.concurrent.duration._

// An example computation
val a: Int > IOs =
  IOs(Math.cos(42).toInt)

// Avoid! Run the application with a specific timeout
val b: Int = 
  App.run(2.minutes)(a)

// Avoid! Run the application without specifying a timeout
val c: Int = 
  App.run(a)
```

## Core Effects

### Aborts: Short Circuiting

```scala
import kyo.aborts._

// The 'get' method "extracts" the value
// from an 'Either' (right projection)
val a: Int > Aborts[String] = 
  Aborts[String].get(Right(1))

// short-circuiting via 'Left'
val b: Int > Aborts[String] = 
  Aborts[String].get(Left("failed!"))

// short-circuiting via 'Fail'
val c: Int > Aborts[String] = 
  Aborts[String].fail("failed!")

// 'catching' automatically catches exceptions
val d: Int > Aborts[Exception] = 
  Aborts[Exception].catching(throw new Exception)
```

### IOs: Side Effects

```scala
import kyo.ios._

def aSideEffect = 1 // placeholder

// 'apply' is used to suspend side effects
val a: Int > IOs = 
  IOs(aSideEffect)

// 'value' is a shorthand to widen 
// a pure value to IOs
val b: Int > IOs = 
  IOs.value(42)

// 'fail' returns a computation that 
// will fail once IOs is handled
val c: Int > IOs = 
  IOs.fail(new Exception)

val a: Int > IOs = 
  IOs(42)

// ** Avoid 'IOs.run', use 'kyo.App' instead. **
val b: Int = 
  IOs.run(a).pure
// ** Avoid 'IOs.run', use 'kyo.App' instead. **
```

### Envs: Dependency Injection

```scala
import kyo.envs._

// Given an interface
trait Database {
  def count: Int > IOs 
}

// The 'Envs' effect can be used to summon an instance.
// Note how the computation produces a 'Database' but at the
// same time requires a 'Database' from its environment
val a: Database > Envs[Database] = 
  Envs[Database].get

// Use the 'Database' to obtain the count
val b: Int > (Envs[Database] with IOs) = 
  a.map(_.count)

// A 'Database' mock implementation
val db = new Database { 
  def count = 1
}

// Handle the 'Envs' effect with the mock database
val c: Int > IOs = 
  Envs[Database].run(db)(b)

// A second interface to be injected
trait Cache {
  def clear: Unit > IOs
}

// A computation that requires two values
val a: Unit > (Envs[Database] with Envs[Cache] with IOs) = 
  Envs[Database].get.map { db =>
    db.count.map {
      case 0 => 
        Envs[Cache].get.map(_.clear)
      case _ => 
        ()
    }
  }
```

### Locals: Scoped Values

```scala
import kyo.locals._

// Locals need to be initialized with a default value
val myLocal: Local[Int] = 
  Locals.init(42)

// The 'get' method returns the current value of the local
val a: Int > IOs = 
  myLocal.get

// The 'let' method assigns a value to a local within the
// scope of a computation. This code produces 43 (42 + 1)
val b: Int > IOs =
  myLocal.let(42)(a.map(_ + 1))
```

### Resources: Resource Safety

```scala
import kyo.resources._
import java.io.Closeable

class Database extends Closeable {
  def count: Int > IOs = 42
  def close() = {}
}

// The `acquire` method accepts any object that 
// implements Java's `Closeable` interface
val db: Database > (Resources with IOs) = 
  Resources.acquire(new Database)

// Use `run` to handle the effect, while also 
// closing the resources utilized by the 
// computationation
val b: Int > IOs = 
  Resources.run(db.map(_.count))

// Example method to execute a function on a database
def withDb[T](f: Database => T > IOs): T > (IOs with Resources) =
  // Initializes the database ('new Database' is a placeholder)
  IOs(new Database).map { db =>
    // Registers `db.close` to be finalized
    Resources.ensure(db.close).map { _ =>
      // Invokes the function
      f(db)
    }
  }

// Execute a function
val a: Int > (IOs with Resources) =
  withDb(_.count)

// Close resources
val b: Int > IOs = 
  Resources.run(a)
```

### Lists: Exploratory Branching

```scala
import kyo.lists._

// Evaluate each of the provided lists.
// Note how 'foreach' takes a 'List[T]'
// and returns a 'T > Lists'
val a: Int > Lists =
  Lists.foreach(List(1, 2, 3, 4))

// 'dropIf' discards the current choice if 
// a condition is not met. Produces a 'List(1, 2)'
// since values greater than 2 are dropped
val b: Int > Lists =
  a.map(v => Lists.dropIf(v > 2).map(_ => v))

// 'drop' unconditionally discards the 
// current choice. Produces a 'List(42)'
// since only the value 1 is transformed
// to 42 and all other values are dropped
val c: Int > Lists = 
  b.map {
    case 1 => 42
    case _ => Lists.drop
  }

// Handle the effect to evaluate all lists 
// and return a 'List' with the results
val d: List[Int] > Any =
  Lists.run(c)
```

### Aspects: Aspect-Oriented Programming

```scala
import kyo.aspects._

// Initialize an aspect that takes a 'Database' and returns
// an 'Int', potentially performing 'IOs' effects
val countAspect: Aspect[Database, Int, IOs] = 
  Aspects.init[Database, Int, IOs]

// The method 'apply' activates the aspect for a computation
def count(db: Database): Int > IOs =
  countAspect(db)(_.count)

// To bind an aspect to an implementation, first create a new 'Cut'
val countPlusOne =
  new Cut[Database, Int, IOs] {
    // The first param is the input of the computation and the second is
    // the computation being handled
    def apply[S](v: Database > S)(f: Database => Int > IOs) =
      v.map(db => f(db).map(_ + 1))
  }

// Bind the 'Cut' to a computation with the 'let' method.
// The first param is the 'Cut' and the second is the computation
// that will run with the custom binding of the aspect
def example(db: Database): Int > IOs =
  countAspect.let(countPlusOne) {
    count(db)
  }

// Another 'Cut' implementation
val countTimesTen =
  new Cut[Database, Int, IOs] {
    def apply[S](v: Database > S)(f: Database => Int > IOs) =
      v.map(db => f(db).map(_ * 10))
  }

// First bind 'countPlusOne' then 'countTimesTen'
// the result will be (db.count + 1) * 10
def example1(db: Database) =
  countAspect.let(countPlusOne) {
    countAspect.let(countTimesTen) {
      count(db)
    }
  }

// First bind 'countTimesTen' then 'countPlusOne'
// the result will be (db.count * 10) + 1
def example2(db: Database) =
  countAspect.let(countTimesTen) {
    countAspect.let(countPlusOne) {
      count(db)
    }
  }

// Cuts can also be composed via `andThen`
def example3(db: Database) =
  countAspect.let(countTimesTen.andThen(countPlusOne)) {
    count(db)
  }
```

