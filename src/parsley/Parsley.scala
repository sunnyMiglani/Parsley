package parsley

import parsley.DeepToken.{Keyword, Operator, RawStringLiteral, StringLiteral}
import parsley.instructions._

import language.existentials
import scala.annotation.tailrec
import scala.collection.mutable
    
// User API
object Parsley
{
    implicit final class LazyParsley[P, +A](p: =>P)(implicit con: P => Parsley[A])
    {
        /**
          * This is the functorial map operation for parsers. When the invokee produces a value, this value is fed through
          * the function `f`.
          *
          * WARNING: This is subject to aggressive optimisations assuming purity; the compiler is permitted to optimise such
          * that the application of `f` actually only happens once at compile time. In order to preserve the behaviour of
          * impure functions, consider using the `unsafe` method before map; `p.unsafe.map(f)`.
          * @param f The mutator to apply to the result of previous parse
          * @return A new parser which parses the same input as the invokee but mutated by function `f`
          */
        def map[B](f: A => B): Parsley[B] = pure(f) <*> p
        /**This combinator is an alias for `map`*/
        def <#>[B](f: A => B): Parsley[B] = map(f)
        /**
          * This is the traditional Monadic binding operator for parsers. When the invokee produces a value, the function
          * `f` is used to produce a new parser that continued the computation.
          *
          * WARNING: There is significant overhead for using flatMap; if possible try to write parsers in an applicative
          * style otherwise try and use the intrinsic parsers provided to replace the flatMap.
          * @param f A function that produces the next parser
          * @return The parser produces from the application of `f` on the result of the last parser
          */
        def flatMap[B](f: A => Parsley[B]): Parsley[B] = new DeepEmbedding.>>=(p, f)
        /**This combinator is an alias for `flatMap`*/
        def >>=[B](f: A => Parsley[B]): Parsley[B] = flatMap(f)
        /**This combinator is defined as `lift2((x, f) => f(x), p, f)`. It is pure syntactic sugar.*/
        def <**>[B](pf: =>Parsley[A => B]): Parsley[B] = lift2[A, A=>B, B]((x, f) => f(x), p, pf)
        /**
          * This is the traditional Alternative choice operator for parsers. Following the parsec semantics precisely,
          * this combinator first tries to parse the invokee. If this is successful, no further action is taken. If the
          * invokee failed *without* consuming input, then `q` is parsed instead. If the invokee did parse input then the
          * whole parser fails. This is done to prevent space leaks and to give good error messages. If this behaviour
          * is not desired, use the `<\>` combinator (or `attempt(this) <|> q`) to parse `q` regardless of how the
          * invokee failed.
          * @param q The parser to run if the invokee failed without consuming input
          * @return The value produced by the invokee if it was successful, or if it failed without consuming input, the
          *         possible result of parsing q.
          */
        def <|>[B >: A](q: =>Parsley[B]): Parsley[B] = new DeepEmbedding.<|>(p, q)
        /**This combinator is defined as `p <|> pure(x)`. It is pure syntactic sugar.*/
        def </>[B >: A](x: B): Parsley[B] = this <|> pure(x)
        /**This combinator is an alias for <|>.*/
        def orElse[B >: A](q: =>Parsley[B]): Parsley[B] = this <|> q
        /**This combinator is an alias for </>.*/
        def getOrElse[B >: A](x: B): Parsley[B] = p </> x
        /**This combinator is defined as `attempt(p) <|> q`. It is pure syntactic sugar.*/
        def <\>[B >: A](q: Parsley[B]): Parsley[B] = attempt(p) <|> q
        /**
          * This is the parser that corresponds to a more optimal version of `p.map(_ => x => x) <*> q`. It performs
          * the parse action of both parsers, in order, but discards the result of the invokee.
          * @param q The parser whose result should be returned
          * @return A new parser which first parses `p`, then `q` and returns the result of `q`
          */
        def *>[A_ >: A, B](q: =>Parsley[B]): Parsley[B] = new DeepEmbedding.*>[A_, B](p, q)
        /**
          * This is the parser that corresponds to a more optimal version of `p.map(x => _ => x) <*> q`. It performs
          * the parse action of both parsers, in order, but discards the result of the second parser.
          * @param q The parser who should be executed but then discarded
          * @return A new parser which first parses `p`, then `q` and returns the result of the `p`
          */
        def <*[B](q: =>Parsley[B]): Parsley[A] = new DeepEmbedding.<*(p, q)
        /**
          * This is the parser that corresponds to `p *> pure(x)` or a more optimal version of `p.map(_ => x)`.
          * It performs the parse action of the invokee but discards its result and then results the value `x` instead
          * @param x The value to be returned after the execution of the invokee
          * @return A new parser which first parses the invokee, then results `x`
          */
        def #>[B](x: B): Parsley[B] = this *> pure(x)
        /**This combinator is an alias for `*>`*/
        def >>[B](q: Parsley[B]): Parsley[B] = this *> q
        /**This parser corresponds to `lift2(_+:_, p, ps)`.*/
        def <+:>[B >: A](ps: =>Parsley[Seq[B]]): Parsley[Seq[B]] = lift2[A, Seq[B], Seq[B]](_ +: _, p, ps)
        /**This parser corresponds to `lift2(_::_, p, ps)`.*/
        def <::>[B >: A](ps: =>Parsley[List[B]]): Parsley[List[B]] = lift2[A, List[B], List[B]](_ :: _, p, ps)
        /**This parser corresponds to `lift2((_, _), p, q)`. For now it is sugar, but in future may be more optimal*/
        def <~>[A_ >: A, B](q: =>Parsley[B]): Parsley[(A_, B)] = lift2[A_, B, (A_, B)]((_, _), p, q)
        /** Filter the value of a parser; if the value returned by the parser matches the predicate `pred` then the
          * filter succeeded, otherwise the parser fails with an empty error
          * @param pred The predicate that is tested against the parser result
          * @return The result of the invokee if it passes the predicate
          */
        def filter(pred: A => Boolean): Parsley[A] = new DeepEmbedding.Ensure(p, pred)
        def withFilter(pred: A => Boolean): Parsley[A] = filter(pred)
        /** Similar to `filter`, except the error message desired is also provided. This allows you to name the message
          * itself.
          * @param pred The predicate that is tested against the parser result
          * @param msg The message used for the error if the input failed the check
          * @return The result of the invokee if it passes the predicate
          */
        def guard(pred: A => Boolean, msg: String): Parsley[A] = new DeepEmbedding.Guard(p, pred, msg)
        /** Similar to `filter`, except the error message desired is also provided. This allows you to name the message
          * itself. The message is provided as a generator, which allows the user to avoid otherwise expensive
          * computation.
          * @param pred The predicate that is tested against the parser result
          * @param msggen Generator function for error message, generating a message based on the result of the parser
          * @return The result of the invokee if it passes the predicate
          */
        def guard(pred: A => Boolean, msggen: A => String): Parsley[A] = new DeepEmbedding.FastGuard(p, pred, msggen)
        /**Alias for guard combinator, taking a fixed message.*/
        def >?>(pred: A => Boolean, msg: String): Parsley[A] = guard(pred, msg)
        /**Alias for guard combinator, taking a dynamic message generator.*/
        def >?>(pred: A => Boolean, msggen: A => String): Parsley[A] = guard(pred, msggen)
        /**Sets the expected message for a parser. If the parser fails then `expected msg` will added to the error*/
        def ?(msg: String): Parsley[A] = new DeepEmbedding.ErrorRelabel(p, msg)
        /** Same as `fail`, except allows for a message generated from the result of the failed parser. In essence, this
          * is equivalent to `p >>= (x => fail(msggen(x))` but requires no expensive computations from the use of `>>=`.
          * @param msggen The generator function for error message, creating a message based on the result of invokee
          * @return A parser that fails if it succeeds, with the given generator used to produce the error message
          */
        def !(msggen: A => String): Parsley[Nothing] = new DeepEmbedding.FastFail(p, msggen)
        /** Same as `unexpected`, except allows for a message generated from the result of the failed parser. In essence,
          * this is equivalent to `p >>= (x => unexpected(x))` but requires no expensive computations from the use of
          * `>>=`
          * @param msggen The generator function for error message, creating a message based on the result of invokee
          * @return A parser that fails if it succeeds, with the given generator used to produce an unexpected message
          */
        def unexpected(msggen: A => String): Parsley[Nothing] = new DeepEmbedding.FastUnexpected(p, msggen)
        /** Transforms this parser into a subroutine; instead of inlining this parser into every use-site (performing
          * optimisations and repeatedly generating code), produces a subroutine-like parser which is jumped to when
          * required. This will introduce runtime overhead, but it is fairly cheap and speeds up the compilation
          * of parsers that are very big and used many times considerably.
          * @return The same parser, but wrapped in a subroutine call
          */
        def unary_+ : Parsley[A] = new DeepEmbedding.Subroutine(p)
        /**
          * Using this method enables debugging functionality for this parser. When it is entered a snapshot is taken and
          * presented on exit. It will signify when a parser is entered and exited as well. Use the break parameter to halt
          * execution on either entry, exit, both or neither.
          * @param name The name to be assigned to this parser
          * @param break The breakpoint properties of this parser, defaults to NoBreak
          */
        def debug[A_ >: A](name: String, break: Breakpoint = NoBreak): Parsley[A_] = new DeepEmbedding.Debug[A_](p, name, break)
    }
    implicit final class LazyAppParsley[A, +B](pf: =>Parsley[A => B])
    {
        /**
          * This is the Applicative application parser. The type of `pf` is `Parsley[A => B]`. Then, given a
          * `Parsley[A]`, we can produce a `Parsley[B]` by parsing `pf` to retrieve `f: A => B`, then parse `px`
          * to receive `x: A` then return `f(x): B`.
          *
          * WARNING: `pure(f) <*> p` is subject to the same aggressive optimisations as `map`. When using impure functions
          * the optimiser may decide to cache the result of the function execution, be sure to use `unsafe` in order to
          * prevent these optimisations.
          * @param px A parser of type A, where the invokee is A => B
          * @return A new parser which parses `pf`, then `px` then applies the value returned by `px` to the function
          *         returned by `pf`
          */
        def <*>(px: =>Parsley[A]): Parsley[B] = new DeepEmbedding.<*>(pf, px)
    }
    implicit final class LazyFlattenParsley[+A](p: =>Parsley[Parsley[A]])
    {
        /**This combinator is an alias for `flatMap(identity)`.*/
        def flatten: Parsley[A] = p >>= identity[Parsley[A]]
    }
    implicit final class LazyMapParsley[A, +B](f: A => B)
    {
        /**This combinator is an alias for `map`*/
        def <#>(p: =>Parsley[A]): Parsley[B] = p.map(f)
    }
    implicit final class LazyChooseParsley[P, +A](pq: =>(P, P))(implicit con: P => Parsley[A])
    {
        private lazy val (p, q) = pq
        /**
          * This serves as a lifted if statement (hence its similar look to a C-style ternary expression).
          * If the parser on the lhs of the operator it is true then execution continues with parser `p`, else
          * control passes to parser `q`. `b ?: (p, q)` is equivalent to `b >>= (b => if (b) p else q)` but does not
          * involve any expensive monadic operations. Note: due to Scala operator associativity laws, this is a
          * right-associative operator, and must be properly bracketed, technically the invokee is the rhs...
          * @param b The parser that yields the condition value
          * @return The result of either `p` or `q` depending on the return value of the invokee
          */
        def ?:(b: =>Parsley[Boolean]): Parsley[A] = new DeepEmbedding.Ternary(b, p, q)
    }

    /** This is the traditional applicative pure function (or monadic return) for parsers. It consumes no input and
      * does not influence the state of the parser, but does return the value provided. Useful to inject pure values
      * into the parsing process.
      * @param x The value to be returned from the parser
      * @return A parser which consumes nothing and returns `x`
      */
    def pure[A](x: A): Parsley[A] = new DeepEmbedding.Pure(x)

    /** Traditionally, `lift2` is defined as `lift2(f, p, q) = p.map(f) <*> q`. However, `f` is actually uncurried,
      * so it's actually more exactly defined as; read `p` and then read `q` then provide their results to function
      * `f`. This is designed to bring higher performance to any curried operations that are not themselves
      * intrinsic.
      * @param f The function to apply to the results of `p` and `q`
      * @param p The first parser to parse
      * @param q The second parser to parse
      * @return `f(x, y)` where `x` is the result of `p` and `y` is the result of `q`.
      */
    def lift2[A, B, C](f: (A, B) => C, p: =>Parsley[A], q: =>Parsley[B]): Parsley[C] = new DeepEmbedding.Lift2(f, p, q)
    /** Traditionally, `lift2` is defined as `lift3(f, p, q, r) = p.map(f) <*> q <*> r`. However, `f` is actually uncurried,
      * so it's actually more exactly defined as; read `p` and then read `q` and then read 'r' then provide their results
      * to function `f`. This is designed to bring higher performance to any curried operations that are not themselves
      * intrinsic.
      * @param f The function to apply to the results of `p` and `q`
      * @param p The first parser to parse
      * @param q The second parser to parse
      * @param r The third parser to parse
      * @return `f(x, y, z)` where `x` is the result of `p`, `y` is the result of `q` and `z` is the result of `r`.
      */
    def lift3[A, B, C, D](f: (A, B, C) => D, p: =>Parsley[A], q: =>Parsley[B], r: =>Parsley[C]): Parsley[D] = new DeepEmbedding.Lift3(f, p, q, r)
    /**This function is an alias for `_.flatten`. Provides namesake to Haskell.*/
    def join[A](p: =>Parsley[Parsley[A]]): Parsley[A] = p.flatten
    /** Given a parser `p`, attempts to parse `p`. If the parser fails, then `attempt` ensures that no input was
      * consumed. This allows for backtracking capabilities, disabling the implicit cut semantics offered by `<|>`.
      * @param p The parser to run
      * @return The result of `p`, or if `p` failed ensures the parser state was as it was on entry.
      */
    def attempt[A](p: =>Parsley[A]): Parsley[A] = new DeepEmbedding.Attempt(p)
    /** Parses `p` without consuming any input. If `p` fails and consumes input then so does `lookAhead(p)`. Combine with
      * `attempt` if this is undesirable.
      * @param p The parser to look ahead at
      * @return The result of the lookahead
      */
    def lookAhead[A](p: =>Parsley[A]): Parsley[A] = new DeepEmbedding.Look(p)
    /**Alias for `p ? msg`.*/
    def label[A](p: Parsley[A], msg: String): Parsley[A] = p ? msg
    /** The `fail(msg)` parser consumes no input and fails with `msg` as the error message */
    def fail(msg: String): Parsley[Nothing] = new DeepEmbedding.Fail(msg)
    /** The `empty` parser consumes no input and fails softly (that is to say, no error message) */
    val empty: Parsley[Nothing] = new DeepEmbedding.Empty
    /** The `unexpected(msg)` parser consumes no input and fails with `msg` as an unexpected error */
    def unexpected(msg: String): Parsley[Nothing] = new DeepEmbedding.Unexpected(msg)
    /** Returns `()`. Defined as `pure(())` but aliased for sugar*/
    val unit: Parsley[Unit] = pure(())
    /** `many(p)` executes the parser `p` zero or more times. Returns a list of the returned values of `p`. */
    def many[A](p: =>Parsley[A]): Parsley[List[A]] = new DeepEmbedding.Many(p)
    /** `skipMany(p)` executes the parser `p` zero or more times and ignores the results. Returns `()` */
    def skipMany[A](p: =>Parsley[A]): Parsley[Unit] = new DeepEmbedding.*>(new DeepEmbedding.SkipMany(p), unit)
    /**
      * Evaluate each of the parsers in `ps` sequentially from left to right, collecting the results.
      * @param ps Parsers to be sequenced
      * @return The list containing results, one from each parser, in order
      */
    def sequence[A](ps: Parsley[A]*): Parsley[List[A]] = ps.foldRight(pure[List[A]](Nil))(_ <::> _)
    /**
      * Like `sequence` but produces a list of parsers to sequence by applying the function `f` to each
      * element in `xs`.
      * @param f The function to map on each element of `xs` to produce parsers
      * @param xs Values to generate parsers from
      * @return The list containing results formed by executing each parser generated from `xs` and `f` in sequence
      */
    def traverse[A, B](f: A => Parsley[B], xs: A*): Parsley[List[B]] = sequence(xs.map(f): _*)
    /**
      * Evaluate each of the parsers in `ps` sequentially from left to right, ignoring the results.
      * @param ps Parsers to be performed
      */
    def skip(ps: Parsley[_]*): Parsley[Unit] = ps.foldRight(unit)(_ *> _)
    /**
      * This parser consumes no input and returns the current line number reached in the input stream
      * @return The line number the parser is currently at
      */
    val line: Parsley[Int] = DeepEmbedding.Line
    /**
      * This parser consumes no input and returns the current column number reached in the input stream
      * @return The column number the parser is currently at
      */
    val col: Parsley[Int] = DeepEmbedding.Col
    /**
      * This parser consumes no input and returns the current position reached in the input stream
      * @return Tuple of line and column number that the parser has reached
      */
    val pos: Parsley[(Int, Int)] = line <~> col
    /**
      * Consumes no input and returns the value stored in one of the parser registers.
      * Note that there are only 4 registers at present.
      * @param v The index of the register to collect from
      * @tparam S The type of the value in register `v` (this will result in a runtime type-check)
      * @return The value stored in register `v` of type `S`
      */
    def get[S](v: Var)(implicit ev: S =!= Nothing): Parsley[S] = new DeepEmbedding.Get(v)
    /**
      * Consumes no input and places the value `x` into register `v`.
      * Note that there are only 4 registers at present.
      * @param v The index of the register to place the value in
      * @param x The value to place in the register
      */
    def put[S](v: Var, x: S): Parsley[Unit] = put(v, pure(x))
    /**
      * Places the result of running `p` into register `v`.
      * Note that there are only 4 registers at present.
      * @param v The index of the register to place the value in
      * @param p The parser to derive the value from
      */
    def put[S](v: Var, p: =>Parsley[S]): Parsley[Unit] = new DeepEmbedding.*>(new DeepEmbedding.Put(v, p), unit)
    /**
      * Modifies the value contained in register `v` using function `f`. It is left to the users responsibility to
      * ensure the types line up. There is no compile-time type checking enforced!
      * Note that there are only 4 registers at present.
      * @param v The index of the register to modify
      * @param f The function used to modify the register
      * @tparam S The type of value currently assumed to be in the register
      */
    def modify[S](v: Var, f: S => S): Parsley[Unit] = new DeepEmbedding.*>(new DeepEmbedding.Modify(v, f), unit)
    /**
      * For the duration of parser `p` the state stored in register `v` is instead set to `x`. The change is undone
      * after `p` has finished.
      * Note that there are only 4 registers at present.
      * @param v The index of the register to modify
      * @param x The value to place in the register `v`
      * @param p The parser to execute with the adjusted state
      * @return The parser that performs `p` with the modified state
      */
    def local[R, A](v: Var, x: R, p: =>Parsley[A]): Parsley[A] = local(v, pure(x), p)
    /**
      * For the duration of parser `q` the state stored in register `v` is instead set to the return value of `p`. The
      * change is undone after `q` has finished.
      * Note that there are only 4 registers at present.
      * @param v The index of the register to modify
      * @param p The parser whose return value is placed in register `v`
      * @param q The parser to execute with the adjusted state
      * @return The parser that performs `q` with the modified state
      */
    def local[R, A](v: Var, p: =>Parsley[R], q: =>Parsley[A]): Parsley[A] = new DeepEmbedding.Local(v, p, q)
    /**
      * For the duration of parser `p` the state stored in register `v` is instead modified with `f`. The change is undone
      * after `p` has finished.
      * Note that there are only 4 registers at present.
      * @param v The index of the register to modify
      * @param f The function used to modify the value in register `v`
      * @param p The parser to execute with the adjusted state
      * @return The parser that performs `p` with the modified state
      */
    def local[R, A](v: Var, f: R => R, p: =>Parsley[A]): Parsley[A] = local(v, get[R](v).map(f), p)
}

// Internals
private [parsley] class CodeGenState
{
    import CodeGenState.CodeGenSubQueueNode
    private [this] var current = 0
    private [this] var queue: CodeGenSubQueueNode = _
    val map: mutable.Map[Parsley[_], Int] = mutable.Map.empty
    def freshLabel(): Int =
    {
        val next = current
        current += 1
        next
    }
    def nlabels: Int = current

    def getSubLabel(p: Parsley[_]) =
    {
        map.getOrElseUpdate(p,
        {
            queue = new CodeGenSubQueueNode(p, queue)
            freshLabel()
        })
    }

    def nextSub(): Parsley[_] =
    {
        val p = queue.p
        queue = queue.tail
        p
    }

    def more: Boolean = queue != null
}
private [parsley] object CodeGenState
{
    private [CodeGenState] class CodeGenSubQueueNode(val p: Parsley[_], val tail: CodeGenSubQueueNode)
}

/**
  * This is the class that encapsulates the act of parsing and running an object of this class with `runParser` will
  * parse the string given as input to `runParser`.
  *
  * Note: In order to construct an object of this class you must use the combinators; the class itself is abstract
  *
  * @author Jamie Willis
  * @version 1
  */
abstract class Parsley[+A] private [parsley]
{
    final protected type InstrBuffer = ResizableArray[Instr]
    final protected type T = Any
    final protected type U = Any
    final protected type V = Any
    /**
      * Using this method signifies that the parser it is invoked on is impure and any optimisations which assume purity
      * are disabled.
      */
    final def unsafe(): Unit = safe = false

    /**
      * Forces the compilation of a parser as opposed to the regular lazy evaluation.
      */
    final def force(): Unit = instrs

    final private [parsley] def pretty: String = instrs.mkString("; ")

    // Internals
    final private [parsley] def optimised(cont: Parsley[A] => Bounce[Parsley[_]])(implicit seen: Set[Parsley[_]], label: UnsafeOption[String], depth: Int): Bounce[Parsley[_]] =
    {
        // 2 is a magic magic number. It yields the fastest speeds possible for the compilation time?!
        if (depth == 2) (if (seen.isEmpty) this else this.fix).preprocess(p => new Thunk(() => cont(p.optimise)))(seen + this, label, 0)
        else (if (seen.isEmpty) this else this.fix).preprocess(p => cont(p.optimise))(seen + this, label, depth+1)
    }
    final private [parsley] var safe = true
    final private [parsley] lazy val instrs: Array[Instr] =
    {
        val instrs: InstrBuffer = new ResizableArray()
        val state = new CodeGenState
        optimised((p: Parsley[A]) => new Chunk(p))(Set.empty, null, 0).run.codeGen(Terminate)(instrs, state).run()
        if (state.map.nonEmpty)
        {
            val end = state.freshLabel()
            instrs += new instructions.Jump(end)
            val map = state.map
            while (state.more)
            {
                val p = state.nextSub()
                val label = map(p)
                instrs += new instructions.Label(label)
                p.codeGen(Terminate)(instrs, state).run()
                instrs += instructions.Return
            }
            instrs += new instructions.Label(end)
        }
        val instrsOversize = instrs.toArray
        val labelMapping = new Array[Int](state.nlabels)
        @tailrec def findLabels(instrs: Array[Instr], labels: Array[Int], n: Int, i: Int = 0, off: Int = 0, nopop: Int = 0): Int = if (i + off < n) instrs(i + off) match
        {
            case label: Label => instrs(i+off) = null; labels(label.i) = i; findLabels(instrs, labels, n, i, off+1)
            case _: NoPush => findLabels(instrs, labels, n, i+1, off, nopop + 1)
            case instructions.Pop if nopop != 0 => instrs(i+off) = null; findLabels(instrs, labels, n, i, off+1, nopop - 1)
            case instructions.Exchange(x) if nopop != 0 => instrs(i+off) = new instructions.Push(x); findLabels(instrs, labels, n, i+1, off, nopop - 1)
            case _ => findLabels(instrs, labels, n, i+1, off)
        } else i
        @tailrec def applyLabels(srcs: Array[Instr], labels: Array[Int], dests: Array[Instr], n: Int, i: Int = 0, off: Int = 0): Unit = if (i < n) srcs(i + off) match
        {
            case null => applyLabels(srcs, labels, dests, n, i, off + 1)
            case jump: JumpInstr =>
                jump.label = labels(jump.label)
                dests(i) = jump
                applyLabels(srcs, labels, dests, n, i + 1, off)
            case table: JumpTable =>
                table.relabel(labels)
                dests(i) = table
                applyLabels(srcs, labels, dests, n, i + 1, off)
            case instr =>
                dests(i) = instr
                applyLabels(srcs, labels, dests, n, i + 1, off)
        }
        val size = findLabels(instrsOversize, labelMapping, instrs.length)
        val instrs_ = new Array[Instr](size)
        applyLabels(instrsOversize, labelMapping, instrs_, instrs_.length)
        instrs_
    }
    final private [this] lazy val pindices: Array[Int] =
    {
        val linstrs = instrs
        val sz = linstrs.length
        var i: Int = 0
        val buff: ResizableArray[Int] = new ResizableArray[Int]()
        while (i < sz)
        {
            // We need to check for calls here too, unlike a call copy.
            if (linstrs(i).isInstanceOf[Stateful] || linstrs(i).isInstanceOf[Call]) buff += i
            i += 1
        }
        buff.toArray
    }
    final private [parsley] def threadSafeInstrs: Array[Instr] =
    {
        val nstateful = pindices.length
        if (nstateful != 0)
        {
            var linstrs = instrs
            val lpindices = pindices
            var i: Int = 0
            while (i < nstateful)
            {
                val j = lpindices(i)
                linstrs(j) = linstrs(j).copy
                i += 1
            }
            linstrs.clone
        }
        instrs
    }
    final private [parsley] def fix(implicit seen: Set[Parsley[_]], label: UnsafeOption[String]): Parsley[A] = if (seen.contains(this)) new DeepEmbedding.Fixpoint(this, label) else this

    // Abstracts
    // Sub-tree optimisation and fixpoint calculation - Bottom-up
    protected def preprocess(cont: Parsley[A] => Bounce[Parsley[_]])(implicit seen: Set[Parsley[_]], label: UnsafeOption[String], depth: Int): Bounce[Parsley[_]]
    // Optimisation - Bottom-up
    private [parsley] def optimise: Parsley[A] = this
    // Peephole optimisation and code generation - Top-down
    private [parsley] def codeGen(cont: =>Continuation)(implicit instrs: InstrBuffer, state: CodeGenState): Continuation
}

private [parsley] object DeepEmbedding
{
    // Core Embedding
    private [parsley] final class Pure[A](private [Pure] val x: A) extends Parsley[A]
    {
        override def preprocess(cont: Parsley[A] => Bounce[Parsley[_]])(implicit seen: Set[Parsley[_]], label: UnsafeOption[String], depth: Int) = cont(this)
        override def codeGen(cont: =>Continuation)(implicit instrs: InstrBuffer, state: CodeGenState) =
        {
            instrs += new instructions.Push(x)
            cont
        }
    }
    private [parsley] final class <*>[A, B](_pf: =>Parsley[A => B], _px: =>Parsley[A]) extends Parsley[B]
    {
        private [<*>] var pf: Parsley[A => B] = _
        private [<*>] var px: Parsley[A] = _
        override def preprocess(cont: Parsley[B] => Bounce[Parsley[_]])(implicit seen: Set[Parsley[_]], label: UnsafeOption[String], depth: Int): Bounce[Parsley[_]] =
            if (label == null && pf != null) cont(this) else _pf.optimised(pf => _px.optimised(px =>
            {
                if (label == null)
                {
                    this.pf = pf
                    this.px = px
                    cont(this)
                }
                else cont(<*>(pf, px))
            }))
        override def optimise: Parsley[B] = (pf, px) match
        {
            // Fusion laws
            case (uf, Pure(x)) if uf.isInstanceOf[Pure[_]] || uf.isInstanceOf[_ <*> _] => uf match
            {
                // first position fusion
                case Pure(f) => new Pure(f(x))
                // second position fusion
                case Pure(f: (T => A => B) @unchecked) <*> (uy: Parsley[T]) =>
                    pf = new Pure((y: T) => f(y)(x))
                    px = uy.asInstanceOf[Parsley[A]]
                    this
                // third position fusion
                case Pure(f: (T => U => A => B) @unchecked) <*> (uy: Parsley[T]) <*> (uz: Parsley[U]) =>
                    pf = <*>(new Pure((y: T) => (z: U) => f(y)(z)(x)), uy)
                    px = uz.asInstanceOf[Parsley[A]]
                    this
                // interchange law: u <*> pure y == pure ($y) <*> u == ($y) <$> u (single instruction, so we benefit at code-gen)
                case _ =>
                    pf = new Pure((f: A => B) => f(x)).asInstanceOf[Parsley[A => B]]
                    px = uf.asInstanceOf[Parsley[A]]
                    this
            }
            // functor law: fmap f (fmap g p) == fmap (f . g) p where fmap f p = pure f <*> p from applicative
            case (Pure(f), Pure(g: (T => A) @unchecked) <*> (u: Parsley[T])) => <*>(new Pure(f.compose(g)), u)
            // TODO: functor law with lift2!
            // right absorption law: mzero <*> p = mzero
            case (z: MZero, _) => z
            /* RE-ASSOCIATION LAWS */
            // re-association law 1: (q *> pf) <*> px = q *> (pf <*> px)
            case (q *> uf, ux) => *>(q, <*>(uf, ux).optimise)
            case (uf, cont: Cont[_, _]) => cont match
            {
                // re-association law 2: pf <*> (px <* q) = (pf <*> px) <* q
                case ux <* v => <*(<*>(uf, ux).optimise, v).optimise
                // re-association law 3: p *> pure x = pure x <* p
                // consequence of re-association law 3: pf <*> (q *> pure x) = (pf <*> pure x) <* q
                case v *> (ux: Pure[_]) => <*(<*>(uf, ux).optimise, v).optimise
                case _ => this
            }
            // consequence of left zero law and monadic definition of <*>, preserving error properties of pf
            case (u, z: MZero) => *>(u, z)
            // interchange law: u <*> pure y == pure ($y) <*> u == ($y) <$> u (single instruction, so we benefit at code-gen)
            case (uf, Pure(x)) =>
                pf = new Pure((f: A => B) => f(x)).asInstanceOf[Parsley[A => B]]
                px = uf.asInstanceOf[Parsley[A]]
                this
            case _ => this
        }
        override def codeGen(cont: =>Continuation)(implicit instrs: InstrBuffer, state: CodeGenState) = pf match
        {
            // pure f <*> p = f <$> p
            case Pure(f) => px match
            {
                case ct@CharTok(c) => instrs += instructions.CharTokFastPerform[Char, B](c, f.asInstanceOf[Char => B], ct.expected); cont
                case st@StringTok(s) => instrs += new instructions.StringTokFastPerform(s, f.asInstanceOf[String => B], st.expected); cont
                case _ =>
                    new Suspended(px.codeGen
                    {
                        instrs += new instructions.Perform(f)
                        cont
                    })
            }
            case _ =>
                new Suspended(pf.codeGen
                {
                    px.codeGen
                    {
                        instrs += instructions.Apply
                        cont
                    }
                })
        }
    }
    private [parsley] final class <|>[A, B](_p: =>Parsley[A], _q: =>Parsley[B]) extends Parsley[B]
    {
        private [<|>] var p: Parsley[A] = _
        private [<|>] var q: Parsley[B] = _
        override def preprocess(cont: Parsley[B] => Bounce[Parsley[_]])(implicit seen: Set[Parsley[_]], label: UnsafeOption[String], depth: Int): Bounce[Parsley[_]] =
            if (label == null && p != null) cont(this) else _p.optimised(p => _q.optimised(q =>
            {
                if (label == null)
                {
                    this.p = p
                    this.q = q
                    cont(this)
                }
                else cont(<|>(p, q))
            }))
        override def optimise: Parsley[B] = (p, q) match
        {
            // left catch law: pure x <|> p = pure x
            case (u: Pure[B], _) => u
            // alternative law: empty <|> p = p
            case (e: Empty, v) if e.expected == null => v
            // alternative law: p <|> empty = p
            case (u: Parsley[B], e: Empty) if e.expected == null => u
            // associative law: (u <|> v) <|> w = u <|> (v <|> w)
            case ((u: Parsley[T]) <|> (v: Parsley[A]), w) =>
                p = u.asInstanceOf[Parsley[A]]
                q = <|>[A, B](v, w).optimise
                this
            case _ => this
        }
        override def codeGen(cont: =>Continuation)(implicit instrs: InstrBuffer, state: CodeGenState) = tablify(this, Nil) match
        {
            // If the tablified list is single element, that implies that this should be generated as normal!
            case _::Nil => p match
            {
                case Attempt(u) => q match
                {
                    case Pure(x) =>
                        val handler = state.freshLabel()
                        instrs += new instructions.PushHandler(handler)
                        new Suspended(u.codeGen
                        {
                            instrs += new instructions.Label(handler)
                            instrs += new instructions.AlwaysRecoverWith[B](x)
                            cont
                        })
                    case v =>
                        val handler = state.freshLabel()
                        val skip = state.freshLabel()
                        instrs += new instructions.PushHandler(handler)
                        new Suspended(u.codeGen
                        {
                            instrs += new instructions.Label(handler)
                            instrs += new instructions.JumpGoodAttempt(skip)
                            v.codeGen
                            {
                                instrs += new instructions.Label(skip)
                                cont
                            }
                        })
                }
                case u => q match
                {
                    case Pure(x) =>
                        val handler = state.freshLabel()
                        val skip = state.freshLabel()
                        instrs += new instructions.InputCheck(handler)
                        new Suspended(u.codeGen
                        {
                            instrs += new instructions.JumpGood(skip)
                            instrs += new instructions.Label(handler)
                            instrs += new instructions.RecoverWith[B](x)
                            instrs += new instructions.Label(skip)
                            cont
                        })
                    case v =>
                        val handler = state.freshLabel()
                        val skip = state.freshLabel()
                        instrs += new instructions.InputCheck(handler)
                        new Suspended(u.codeGen
                        {
                            instrs += new instructions.JumpGood(skip)
                            instrs += new instructions.Label(handler)
                            instrs += instructions.Catch
                            v.codeGen
                            {
                                instrs += new instructions.Label(skip)
                                cont
                            }
                        })
                }

            }
            // In case of None'd list, the codeGen cont continues by codeGenning that p, else we are done for this tree, call cont!
            case tablified =>
                // This list is backwards :)
                val needsDefault = tablified.head._2.isDefined
                val end = state.freshLabel()
                val default = state.freshLabel()
                val (roots, leads, ls, expecteds) = foldTablified(tablified, state, mutable.Map.empty, Nil, Nil, Nil)
                instrs += new instructions.JumpTable(leads, ls, default, expecteds)
                new Suspended(codeGenRoots(
                {
                    instrs += instructions.Catch
                    instrs += new instructions.Label(default)
                    if (needsDefault)
                    {
                        instrs += new instructions.Empty(null)
                        instrs += new instructions.Label(end)
                        cont
                    }
                    else
                    {
                        tablified.head._1.codeGen
                        {
                            instrs += new instructions.Label(end)
                            cont
                        }
                    }
                }, roots, ls, end))
        }
        def codeGenRoots(cont: =>Continuation, roots: List[List[Parsley[_]]], ls: List[Int], end: Int)(implicit instrs: InstrBuffer, state: CodeGenState): Continuation = roots match
        {
            case root::roots_ =>
                instrs += new instructions.Label(ls.head)
                codeGenAlternatives(root)
                {
                    instrs += new instructions.JumpGood(end)
                    codeGenRoots(cont, roots_, ls.tail, end)
                }
            case Nil => cont
        }
        def codeGenAlternatives(alts: List[Parsley[_]])(cont: =>Continuation)(implicit instrs: InstrBuffer, state: CodeGenState): Continuation = (alts: @unchecked) match
        {
            case alt::Nil => alt.codeGen(cont)
            case Attempt(alt)::alts_ =>
                val handler = state.freshLabel()
                val skip = state.freshLabel()
                instrs += new instructions.PushHandler(handler)
                alt.codeGen
                {
                    instrs += new instructions.Label(handler)
                    instrs += new instructions.JumpGoodAttempt(skip)
                    codeGenAlternatives(alts_)
                    {
                        instrs += new instructions.Label(skip)
                        cont
                    }
                }
            case alt::alts_ =>
                val handler = state.freshLabel()
                val skip = state.freshLabel()
                instrs += new instructions.InputCheck(handler)
                alt.codeGen
                {
                    instrs += new instructions.JumpGood(skip)
                    instrs += new instructions.Label(handler)
                    instrs += instructions.Catch
                    codeGenAlternatives(alts_)
                    {
                        instrs += new instructions.Label(skip)
                        cont
                    }
                }
        }
        @tailrec def foldTablified(tablified: List[(Parsley[_], Option[Parsley[_]])], labelGen: CodeGenState,
                                   roots: mutable.Map[Char, List[Parsley[_]]],
                                   leads: List[Char],
                                   labels: List[Int],
                                   expecteds: List[UnsafeOption[String]]):
            (List[List[Parsley[_]]], List[Char], List[Int], List[UnsafeOption[String]]) = tablified match
        {
            case (_, None)::tablified_ => foldTablified(tablified_, labelGen, roots, leads, labels, expecteds)
            case (root, Some(lead))::tablified_ =>
                val (c, expected) = lead match
                {
                    case ct@CharTok(d) => (d, ct.expected)
                    case st@StringTok(s) => (s.head, if (st.expected == null) "\"" + s + "\"" else st.expected)
                    case kw@Keyword(k) => (k.head, if (kw.expected == null) k else kw.expected)
                    case op@Operator(o) => (o.head, if (op.expected == null) o else op.expected)
                    case sl: StringLiteral => ('"', if (sl.expected == null) "string" else sl.expected)
                    case rs: RawStringLiteral => ('"', if (rs.expected == null) "string" else rs.expected)
                }
                if (roots.contains(c))
                {
                    roots.update(c, root::roots(c))
                    foldTablified(tablified_, labelGen, roots, leads, labelGen.freshLabel() :: labels, expected :: expecteds)
                }
                else
                {
                    roots.update(c, root::Nil)
                    foldTablified(tablified_, labelGen, roots, c::leads, labelGen.freshLabel() :: labels, expected :: expecteds)
                }
            case Nil => (leads.map(roots(_)), leads, labels, expecteds)
        }
        @tailrec private def tablable(p: Parsley[_]): Option[Parsley[_]] = p match
        {
            // CODO: Numeric parsers by leading digit (This one would require changing the foldTablified function a bit)
            case t@(_: CharTok | _: StringTok | _: Keyword | _: StringLiteral | _: RawStringLiteral | _: Operator) => Some(t)
            case Attempt(t) => tablable(t)
            case (_: Pure[_]) <*> t => tablable(t)
            case Lift2(_, t, _) => tablable(t)
            case Lift3(_, t, _, _) => tablable(t)
            case t <*> _ => tablable(t)
            case t *> _ => tablable(t)
            case t <* _ => tablable(t)
            case _ => None
        }
        @tailrec private [DeepEmbedding] def tablify(p: Parsley[_], acc: List[(Parsley[_], Option[Parsley[_]])]): List[(Parsley[_], Option[Parsley[_]])] = p match
        {
            case u <|> v =>
                val leading = tablable(u)
                if (leading.isDefined) tablify(v, (u, leading)::acc)
                else (p, None)::acc
            case _ => (p, tablable(p))::acc
        }
    }
    private [parsley] final class >>=[A, B](_p: =>Parsley[A], private [>>=] var f: A => Parsley[B], val expected: UnsafeOption[String] = null) extends Parsley[B]
    {
        private [>>=] var p: Parsley[A] = _
        override def preprocess(cont: Parsley[B] => Bounce[Parsley[_]])(implicit seen: Set[Parsley[_]], label: UnsafeOption[String], depth: Int) =
            if (label == null && p != null) cont(this) else _p.optimised(p =>
            {
                if (label == null)
                {
                    this.p = p
                    cont(this)
                }
                else cont(>>=(p, f, label))
            })
        override def optimise: Parsley[B] = p match
        {
            // CODO: We need to try and identify the fixpoints in the optimised binds, so we can remove the call instructions
            // monad law 1: pure x >>= f = f x
            case Pure(x) => new Fixpoint(f(x), expected)
            // char/string x = char/string x *> pure x and monad law 1
            case p@CharTok(c) => *>(p, new Fixpoint(f(c.asInstanceOf[A]), expected))
            case p@StringTok(s) => *>(p, new Fixpoint(f(s.asInstanceOf[A]), expected))
            // (q *> p) >>= f = q *> (p >>= f)
            case u *> v => *>(u, >>=(v, f, expected).optimise)
            // monad law 3: (m >>= g) >>= f = m >>= (\x -> g x >>= f) Note: this *could* help if g x ended with a pure, since this would be optimised out!
            case (m: Parsley[T] @unchecked) >>= (g: (T => A) @unchecked) =>
                p = m.asInstanceOf[Parsley[A]]
                f = (x: T) => >>=(g(x), f, expected).optimise
                this
            // monadplus law (left zero)
            case z: MZero => z
            case _ => this
        }
        override def codeGen(cont: =>Continuation)(implicit instrs: InstrBuffer, state: CodeGenState) =
        {
            new Suspended(p.codeGen
            {
                instrs += new instructions.DynCall[A](x => f(x).instrs, expected)
                cont
            })
        }
    }
    private [parsley] final class Satisfy(private [Satisfy] val f: Char => Boolean, val expected: UnsafeOption[String] = null) extends Parsley[Char]
    {
        override def preprocess(cont: Parsley[Char] => Bounce[Parsley[_]])(implicit seen: Set[Parsley[_]], label: UnsafeOption[String], depth: Int) =
        {
            if (label == null) cont(this)
            else cont(new Satisfy(f, label))
        }
        override def optimise = this
        override def codeGen(cont: =>Continuation)(implicit instrs: InstrBuffer, state: CodeGenState) =
        {
            instrs += new instructions.Satisfies(f, expected)
            cont
        }
    }
    private [parsley] abstract class Cont[A, +B] extends Parsley[B]
    {
        def result: Parsley[B]
        def discard: Parsley[A]
        def copy[B_ >: B](prev: Parsley[A], next: Parsley[B_]): Cont[A, B_]
    }
    private [parsley] final class *>[A, B](_p: =>Parsley[A], _q: =>Parsley[B]) extends Cont[A, B]
    {
        private [*>] var p: Parsley[A] = _
        private [*>] var q: Parsley[B] = _
        override def preprocess(cont: Parsley[B] => Bounce[Parsley[_]])(implicit seen: Set[Parsley[_]], label: UnsafeOption[String], depth: Int): Bounce[Parsley[_]] =
            if (label == null && p != null) cont(this) else _p.optimised(p => _q.optimised(q =>
            {
                if (label == null)
                {
                    this.p = p
                    this.q = q
                    cont(this)
                }
                else cont(*>(p, q))
            }))
        @tailrec override def optimise: Parsley[B] = p match
        {
            // pure _ *> p = p
            case _: Pure[_] => q
            // p *> pure _ *> q = p *> q
            case u *> (_: Pure[_]) =>
                p = u.asInstanceOf[Parsley[A]]
                optimise
            case CharTok(c) if q.isInstanceOf[CharTok] || q.isInstanceOf[StringTok] => q match
            {
                // char(c) *> char(d) = string(cd) *> pure(d)
                case CharTok(d) =>
                    p = new StringTok(c.toString + d).asInstanceOf[Parsley[A]]
                    q = new Pure(d).asInstanceOf[Parsley[B]]
                    optimise
                // char(c) *> string(s) = string(cs) *> pure(s)
                case StringTok(s) =>
                    p = new StringTok(c.toString + s).asInstanceOf[Parsley[A]]
                    q = new Pure(s).asInstanceOf[Parsley[B]]
                    optimise
            }
            case StringTok(s) if q.isInstanceOf[CharTok] || q.isInstanceOf[StringTok] => q match
            {
                // string(s) *> char(c) = string(sc) *> pure(c)
                case CharTok(c) =>
                    p = new StringTok(s + c).asInstanceOf[Parsley[A]]
                    q = new Pure(c).asInstanceOf[Parsley[B]]
                    optimise
                // string(s) *> string(t) = string(st) *> pure(t)
                case StringTok(t) =>
                    p = new StringTok(s + t).asInstanceOf[Parsley[A]]
                    q = new Pure(t).asInstanceOf[Parsley[B]]
                    optimise
            }
            // mzero *> p = mzero (left zero and definition of *> in terms of >>=)
            case z: MZero => z
            case u => q match
            {
                // re-association - normal form of Then chain is to have result at the top of tree
                case v *> w =>
                    p = *>(u, v).asInstanceOf[Parsley[A]].optimise
                    q = w
                    optimise
                case _ => this
            }
        }
        override def codeGen(cont: =>Continuation)(implicit instrs: InstrBuffer, state: CodeGenState) = q match
        {
            case Pure(x) => p match
            {
                case (ct@CharTok(c)) => instrs += instructions.CharTokFastPerform[Char, B](c, _ => x, ct.expected); cont
                case (st@StringTok(s)) => instrs += new instructions.StringTokFastPerform(s, _ => x, st.expected); cont
                case (st@Satisfy(f)) => instrs += new instructions.SatisfyExchange(f, x, st.expected); cont
                case u =>
                    new Suspended(u.codeGen
                    {
                        instrs += new instructions.Exchange(x)
                        cont
                    })
            }
            case v =>
                new Suspended(p.codeGen
                {
                    instrs += instructions.Pop
                    v.codeGen(cont)
                })
        }
        override def discard: Parsley[A] = p
        override def result: Parsley[B] = q
        override def copy[B_ >: B](prev: Parsley[A], next: Parsley[B_]): A *> B_ = *>(prev, next)
    }
    private [parsley] final class <*[A, B](_p: =>Parsley[A], _q: =>Parsley[B]) extends Cont[B, A]
    {
        private [<*] var p: Parsley[A] = _
        private [<*] var q: Parsley[B] = _
        override def preprocess(cont: Parsley[A] => Bounce[Parsley[_]])(implicit seen: Set[Parsley[_]], label: UnsafeOption[String], depth: Int) =
            if (label == null && p != null) cont(this) else _p.optimised(p => _q.optimised(q =>
            {
                if (label == null)
                {
                    this.p = p
                    this.q = q
                    cont(this)
                }
                else cont(<*(p, q))
            }))
        @tailrec override def optimise: Parsley[A] = q match
        {
            // p <* pure _ = p
            case _: Pure[_] => p
            // p <* (q *> pure _) = p <* q
            case v *> (_: Pure[_]) =>
                q = v.asInstanceOf[Parsley[B]]
                optimise
            case CharTok(d) if p.isInstanceOf[CharTok] || p.isInstanceOf[StringTok] => p match
            {
                // char(c) <* char(d) = string(cd) *> pure(c)
                case CharTok(c) => *>(new StringTok(c.toString + d), new Pure(c)).asInstanceOf[Parsley[A]]
                // string(s) <* char(d) = string(sd) *> pure(s)
                case StringTok(s) => *>(new StringTok(s + d), new Pure(s)).asInstanceOf[Parsley[A]]
            }
            case StringTok(t) if p.isInstanceOf[CharTok] || p.isInstanceOf[StringTok]  => p match
            {
                // char(c) <* string(t) = string(ct) *> pure(c)
                case CharTok(c) => *>(new StringTok(c.toString + t), new Pure(c)).asInstanceOf[Parsley[A]]
                // string(s) <* string(t) = string(st) *> pure(s)
                case StringTok(s) => *>(new StringTok(s + t), new Pure(s)).asInstanceOf[Parsley[A]]
            }
            // p <* mzero = p *> mzero (by preservation of error messages and failure properties) - This moves the pop instruction after the failure
            case z: MZero => *>(p, z)
            case w => p match
            {
                // re-association law 3: pure x <* p = p *> pure x
                case u: Pure[_] => *>(w, u).optimise
                // mzero <* p = mzero (left zero law and definition of <* in terms of >>=)
                case z: MZero => z
                // re-association - normal form of Prev chain is to have result at the top of tree
                case u <* v =>
                    p = u
                    q = <*(v, w).asInstanceOf[Parsley[B]].optimise
                    optimise
                case _ => this
            }
        }
        override def codeGen(cont: =>Continuation)(implicit instrs: InstrBuffer, state: CodeGenState) = (p, q) match
        {
            case (Pure(x), ct@CharTok(c)) => instrs += instructions.CharTokFastPerform[Char, A](c, _ => x, ct.expected); cont
            case (Pure(x), st@StringTok(s)) => instrs += new instructions.StringTokFastPerform(s, _ => x, st.expected); cont
            case (Pure(x), st@Satisfy(f)) => instrs += new instructions.SatisfyExchange(f, x, st.expected); cont
            case (Pure(x), v) =>
                new Suspended(v.codeGen
                {
                    instrs += new instructions.Exchange(x)
                    cont
                })
            case _ =>
                new Suspended(p.codeGen
                {
                    q.codeGen
                    {
                        instrs += instructions.Pop
                        cont
                    }
                })
        }
        override def discard: Parsley[B] = q
        override def result: Parsley[A] = p
        override def copy[A_ >: A](prev: Parsley[B], next: Parsley[A_]): <*[A_, B] = <*(next, prev)
    }
    private [parsley] final class Attempt[A](_p: =>Parsley[A]) extends Parsley[A]
    {
        private [Attempt] var p: Parsley[A] = _
        override def preprocess(cont: Parsley[A] => Bounce[Parsley[_]])(implicit seen: Set[Parsley[_]], label: UnsafeOption[String], depth: Int) =
            if (label == null && p != null) cont(this) else _p.optimised(p =>
            {
                if (label == null)
                {
                    this.p = p
                    cont(this)
                }
                else cont(Attempt(p))
            })
        override def codeGen(cont: =>Continuation)(implicit instrs: InstrBuffer, state: CodeGenState) =
        {
            val handler = state.freshLabel()
            instrs += new instructions.PushHandler(handler)
            p.codeGen
            {
                instrs += new instructions.Label(handler)
                instrs += instructions.Attempt
                cont
            }
        }
    }
    private [parsley] final class Look[A](_p: =>Parsley[A]) extends Parsley[A]
    {
        private [Look] var p: Parsley[A] = _
        override def preprocess(cont: Parsley[A] => Bounce[Parsley[_]])(implicit seen: Set[Parsley[_]], label: UnsafeOption[String], depth: Int) =
            if (label == null && p != null) cont(this) else _p.optimised(p =>
            {
                if (label == null)
                {
                    this.p = p
                    cont(this)
                }
                else cont(Look(p))
            })
        override def codeGen(cont: =>Continuation)(implicit instrs: InstrBuffer, state: CodeGenState) =
        {
            val handler = state.freshLabel()
            instrs += new instructions.PushHandler(handler)
            p.codeGen
            {
                instrs += new instructions.Label(handler)
                instrs += instructions.Look
                cont
            }
        }
    }
    private [parsley] sealed trait MZero extends Parsley[Nothing]
    private [parsley] class Empty(val expected: UnsafeOption[String] = null) extends MZero
    {
        override def preprocess(cont: Parsley[Nothing] => Bounce[Parsley[_]])(implicit seen: Set[Parsley[_]], label: UnsafeOption[String], depth: Int) =
        {
            if (label == null) cont(this)
            else cont(new Empty(label))
        }
        override def codeGen(cont: =>Continuation)(implicit instrs: InstrBuffer, state: CodeGenState) =
        {
            instrs += new instructions.Empty(expected)
            cont
        }
    }
    private [parsley] final class Fail(private [Fail] val msg: String, val expected: UnsafeOption[String] = null) extends MZero
    {
        override def preprocess(cont: Parsley[Nothing] => Bounce[Parsley[_]])(implicit seen: Set[Parsley[_]], label: UnsafeOption[String], depth: Int) =
        {
            if (label == null) cont(this)
            else cont(new Fail(msg, label))
        }
        override def codeGen(cont: =>Continuation)(implicit instrs: InstrBuffer, state: CodeGenState) =
        {
            instrs += new instructions.Fail(msg, expected)
            cont
        }
    }
    private [parsley] final class Unexpected(private [Unexpected] val msg: String, val expected: UnsafeOption[String] = null) extends MZero
    {
        override def preprocess(cont: Parsley[Nothing] => Bounce[Parsley[_]])(implicit seen: Set[Parsley[_]], label: UnsafeOption[String], depth: Int) =
        {
            if (label == null) cont(this)
            else cont(new Unexpected(msg, label))
        }
        override def codeGen(cont: =>Continuation)(implicit instrs: InstrBuffer, state: CodeGenState) =
        {
            instrs += new instructions.Unexpected(msg, expected)
            cont
        }
    }
    private [parsley] final class Fixpoint[+A](_p: =>Parsley[A], val expected: UnsafeOption[String] = null) extends Parsley[A]
    {
        private [Fixpoint] lazy val p = _p
        override def preprocess(cont: Parsley[A] => Bounce[Parsley[_]])(implicit seen: Set[Parsley[_]], label: UnsafeOption[String], depth: Int) =
        {
            if (label == null) cont(this)
            else cont(new Fixpoint(_p, label))
        }
        override def codeGen(cont: =>Continuation)(implicit instrs: InstrBuffer, state: CodeGenState) =
        {
            instrs += new instructions.Call(p, expected)
            cont
        }
    }
    private [parsley] final class Subroutine[A](_p: =>Parsley[A], val expected: UnsafeOption[String] = null) extends Parsley[A]
    {
        private [Subroutine] var p: Parsley[A] = _

        override def preprocess(cont: Parsley[A] => Bounce[Parsley[_]])(implicit seen: Set[Parsley[_]], label: UnsafeOption[String], depth: Int) =
        {
            if (p == null) _p.optimised(p =>
            {
                if (label == null)
                {
                    this.p = p
                    cont(this)
                }
                else cont(Subroutine(p, label))
            })(seen, null, depth)
            else if (label == null) cont(this)
            else cont(Subroutine(p, label))
        }
        override def codeGen(cont: => Continuation)(implicit instrs: InstrBuffer, state: CodeGenState) =
        {
            val label = state.getSubLabel(p)
            instrs += new instructions.GoSub(label, expected)
            cont
        }
    }
    // Intrinsic Embedding
    private [parsley] final class CharTok(private [CharTok] val c: Char, val expected: UnsafeOption[String] = null) extends Parsley[Char]
    {
        override def preprocess(cont: Parsley[Char] => Bounce[Parsley[_]])(implicit seen: Set[Parsley[_]], label: UnsafeOption[String], depth: Int) =
        {
            if (label == null) cont(this)
            else cont(new CharTok(c, label))
        }
        override def codeGen(cont: =>Continuation)(implicit instrs: InstrBuffer, state: CodeGenState) =
        {
            instrs += instructions.CharTok(c, expected)
            cont
        }
    }
    private [parsley] final class StringTok(private [StringTok] val s: String, val expected: UnsafeOption[String] = null) extends Parsley[String]
    {
        override def preprocess(cont: Parsley[String] => Bounce[Parsley[_]])(implicit seen: Set[Parsley[_]], label: UnsafeOption[String], depth: Int) =
        {
            if (label == null) cont(this)
            else cont(new StringTok(s, label))
        }
        override def optimise = s match
        {
            case "" => new Pure("")
            case _ => this
        }
        override def codeGen(cont: =>Continuation)(implicit instrs: InstrBuffer, state: CodeGenState) =
        {
            instrs += new instructions.StringTok(s, expected)
            cont
        }
    }
    // TODO: Perform applicative fusion optimisations
    private [parsley] final class Lift2[A, B, +C](private [Lift2] val f: (A, B) => C, _p: =>Parsley[A], _q: =>Parsley[B]) extends Parsley[C]
    {
        private [Lift2] var p: Parsley[A] = _
        private [Lift2] var q: Parsley[B] = _
        override def preprocess(cont: Parsley[C] => Bounce[Parsley[_]])(implicit seen: Set[Parsley[_]], label: UnsafeOption[String], depth: Int) =
            if (label == null && p != null) cont(this) else _p.optimised(p => _q.optimised(q =>
            {
                if (label == null)
                {
                    this.p = p
                    this.q = q
                    cont(this)
                }
                else cont(Lift2(f, p, q))
            }))
        override def codeGen(cont: =>Continuation)(implicit instrs: InstrBuffer, state: CodeGenState) =
        {
            new Suspended(p.codeGen
            {
                q.codeGen
                {
                    instrs += new instructions.Lift2(f)
                    cont
                }
            })
        }
    }
    private [parsley] final class Lift3[A, B, C, +D](private [Lift3] val f: (A, B, C) => D, _p: =>Parsley[A], _q: =>Parsley[B], _r: =>Parsley[C]) extends Parsley[D]
    {
        private [Lift3] var p: Parsley[A] = _
        private [Lift3] var q: Parsley[B] = _
        private [Lift3] var r: Parsley[C] = _
        override def preprocess(cont: Parsley[D] => Bounce[Parsley[_]])(implicit seen: Set[Parsley[_]], label: UnsafeOption[String], depth: Int) =
            if (label == null && p != null) cont(this) else _p.optimised(p => _q.optimised(q => _r.optimised(r =>
            {
                if (label == null)
                {
                    this.p = p
                    this.q = q
                    this.r = r
                    cont(this)
                }
                else cont(Lift3(f, p, q, r))
            })))
        override def codeGen(cont: =>Continuation)(implicit instrs: InstrBuffer, state: CodeGenState) =
        {
            new Suspended(p.codeGen
            {
                q.codeGen
                {
                    r.codeGen
                    {
                        instrs += new instructions.Lift3(f)
                        cont
                    }
                }
            })
        }
    }
    private [parsley] final class FastFail[A](_p: =>Parsley[A], private [FastFail] val msggen: A => String, val expected: UnsafeOption[String] = null) extends MZero
    {
        private [FastFail] var p: Parsley[A] = _
        override def preprocess(cont: Parsley[Nothing] => Bounce[Parsley[_]])(implicit seen: Set[Parsley[_]], label: UnsafeOption[String], depth: Int) =
            if (label == null && p != null) cont(this) else _p.optimised(p =>
            {
                if (label == null)
                {
                    this.p = p
                    cont(this)
                }
                else cont(FastFail(p, msggen, label))
            })
        override def optimise = p match
        {
            case Pure(x) => new Fail(msggen(x))
            case z: MZero => z
            case _ => this
        }
        override def codeGen(cont: =>Continuation)(implicit instrs: InstrBuffer, state: CodeGenState) =
        {
            p.codeGen
            {
                instrs += new instructions.FastFail(msggen, expected)
                cont
            }
        }
    }
    private [parsley] final class FastUnexpected[A](_p: =>Parsley[A], private [FastUnexpected] val msggen: A => String, val expected: UnsafeOption[String] = null) extends MZero
    {
        private [FastUnexpected] var p: Parsley[A] = _
        override def preprocess(cont: Parsley[Nothing] => Bounce[Parsley[_]])(implicit seen: Set[Parsley[_]], label: UnsafeOption[String], depth: Int) =
            if (label == null && p != null) cont(this) else _p.optimised(p =>
            {
                if (label == null)
                {
                    this.p = p
                    cont(this)
                }
                else cont(FastUnexpected(p, msggen, label))
            })
        override def optimise = p match
        {
            case Pure(x) => new Unexpected(msggen(x))
            case z: MZero => z
            case _ => this
        }
        override def codeGen(cont: =>Continuation)(implicit instrs: InstrBuffer, state: CodeGenState) =
        {
            p.codeGen
            {
                instrs += new instructions.FastUnexpected(msggen, expected)
                cont
            }
        }
    }
    private [parsley] final class Ensure[A](_p: =>Parsley[A], private [Ensure] val pred: A => Boolean, val expected: UnsafeOption[String] = null) extends Parsley[A]
    {
        private [Ensure] var p: Parsley[A] = _
        override def preprocess(cont: Parsley[A] => Bounce[Parsley[_]])(implicit seen: Set[Parsley[_]], label: UnsafeOption[String], depth: Int) =
            if (label == null && p != null) cont(this) else _p.optimised(p =>
            {
                if (label == null)
                {
                    this.p = p
                    cont(this)
                }
                else cont(Ensure(p, pred, label))
            })
        override def optimise = p match
        {
            case px@Pure(x) => if (pred(x)) px else new Empty
            case _ => this
        }
        override def codeGen(cont: =>Continuation)(implicit instrs: InstrBuffer, state: CodeGenState) =
        {
            p.codeGen
            {
                instrs += new instructions.Ensure(pred, expected)
                cont
            }
        }
    }
    private [parsley] final class Guard[A](_p: =>Parsley[A], private [Guard] val pred: A => Boolean, private [Guard] val msg: String, val expected: UnsafeOption[String] = null) extends Parsley[A]
    {
        private [Guard] var p: Parsley[A] = _
        override def preprocess(cont: Parsley[A] => Bounce[Parsley[_]])(implicit seen: Set[Parsley[_]], label: UnsafeOption[String], depth: Int) =
            if (label == null && p != null) cont(this) else _p.optimised(p =>
            {
                if (label == null)
                {
                    this.p = p
                    cont(this)
                }
                else cont(Guard(p, pred, msg, label))
            })
        override def optimise = p match
        {
            case px@Pure(x) => if (pred(x)) px else new Fail(msg)
            case _ => this
        }
        override def codeGen(cont: =>Continuation)(implicit instrs: InstrBuffer, state: CodeGenState) =
        {
            p.codeGen
            {
                instrs += new instructions.Guard(pred, msg, expected)
                cont
            }
        }
    }
    private [parsley] final class FastGuard[A](_p: =>Parsley[A], private [FastGuard] val pred: A => Boolean, private [FastGuard] val msggen: A => String, val expected: UnsafeOption[String] = null) extends Parsley[A]
    {
        private [FastGuard] var p: Parsley[A] = _
        override def preprocess(cont: Parsley[A] => Bounce[Parsley[_]])(implicit seen: Set[Parsley[_]], label: UnsafeOption[String], depth: Int) =
            if (label == null && p != null) cont(this) else _p.optimised(p =>
            {
                if (label == null)
                {
                    this.p = p
                    cont(this)
                }
                else cont(FastGuard(p, pred, msggen, label))
            })
        override def optimise = p match
        {
            case px@Pure(x) => if (pred(x)) px else new Fail(msggen(x))
            case _ => this
        }
        override def codeGen(cont: =>Continuation)(implicit instrs: InstrBuffer, state: CodeGenState) =
        {
            p.codeGen
            {
                instrs += new instructions.FastGuard(pred, msggen, expected)
                cont
            }
        }
    }
    private [parsley] final class Many[A](_p: =>Parsley[A]) extends Parsley[List[A]]
    {
        private [Many] var p: Parsley[A] = _
        override def preprocess(cont: Parsley[List[A]] => Bounce[Parsley[_]])(implicit seen: Set[Parsley[_]], label: UnsafeOption[String], depth: Int) =
            if (label == null && p != null) cont(this) else _p.optimised(p =>
            {
                if (label == null)
                {
                    this.p = p
                    cont(this)
                }
                else cont(Many(p))
            })
        override def optimise = p match
        {
            case _: Pure[A] => throw new Exception("many given parser which consumes no input")
            case _: MZero => new Pure(Nil)
            case _ => this
        }
        override def codeGen(cont: =>Continuation)(implicit instrs: InstrBuffer, state: CodeGenState) =
        {
            val body = state.freshLabel()
            val handler = state.freshLabel()
            instrs += new instructions.InputCheck(handler)
            instrs += new instructions.Label(body)
            new Suspended(p.codeGen
            {
                instrs += new instructions.Label(handler)
                instrs += new instructions.Many(body)
                cont
            })
        }
    }
    private [parsley] final class SkipMany[A](_p: =>Parsley[A]) extends Parsley[Nothing]
    {
        private [SkipMany] var p: Parsley[A] = _
        override def preprocess(cont: Parsley[Nothing] => Bounce[Parsley[_]])(implicit seen: Set[Parsley[_]], label: UnsafeOption[String], depth: Int) =
            if (label == null && p != null) cont(this) else _p.optimised(p =>
            {
                if (label == null)
                {
                    this.p = p
                    cont(this)
                }
                else cont(SkipMany(p))
            })
        override def optimise = p match
        {
            case _: Pure[A] => throw new Exception("skipMany given parser which consumes no input")
            case _: MZero => new Pure(()).asInstanceOf[Parsley[Nothing]]
            case _ => this
        }
        override def codeGen(cont: =>Continuation)(implicit instrs: InstrBuffer, state: CodeGenState) =
        {
            val body = state.freshLabel()
            val handler = state.freshLabel()
            instrs += new instructions.InputCheck(handler)
            instrs += new instructions.Label(body)
            new Suspended(p.codeGen
            {
                instrs += new instructions.Label(handler)
                instrs += new instructions.SkipMany(body)
                cont
            })
        }
    }
    private [parsley] final class ChainPost[A](_p: =>Parsley[A], _op: =>Parsley[A => A]) extends Parsley[A]
    {
        private [ChainPost] var p: Parsley[A] = _
        private [ChainPost] var op: Parsley[A => A] = _
        override def preprocess(cont: Parsley[A] => Bounce[Parsley[_]])(implicit seen: Set[Parsley[_]], label: UnsafeOption[String], depth: Int) =
            if (label == null && p != null) cont(this) else _p.optimised(p => _op.optimised(op =>
            {
                if (label == null)
                {
                    this.p = p
                    this.op = op
                    cont(this)
                }
                else cont(ChainPost(p, op))
            }))
        override def optimise = op match
        {
            case _: Pure[A => A] => throw new Exception("left chain given parser which consumes no input")
            case _: MZero => p
            case _ => this
        }
        override def codeGen(cont: =>Continuation)(implicit instrs: InstrBuffer, state: CodeGenState) =
        {
            val body = state.freshLabel()
            val handler = state.freshLabel()
            new Suspended(p.codeGen
            {
                instrs += new instructions.InputCheck(handler)
                instrs += new instructions.Label(body)
                op.codeGen
                {
                    instrs += new instructions.Label(handler)
                    instrs += new instructions.ChainPost(body)
                    cont
                }
            })
        }
    }
    private [parsley] final class ChainPre[A](_p: =>Parsley[A], _op: =>Parsley[A => A]) extends Parsley[A]
    {
        private [ChainPre] var p: Parsley[A] = _
        private [ChainPre] var op: Parsley[A => A] = _
        override def preprocess(cont: Parsley[A] => Bounce[Parsley[_]])(implicit seen: Set[Parsley[_]], label: UnsafeOption[String], depth: Int) =
            if (label == null && p != null) cont(this) else _p.optimised(p => _op.optimised(op =>
            {
                if (label == null)
                {
                    this.p = p
                    this.op = op
                    cont(this)
                }
                else cont(ChainPre(p, op))
            }))
        override def optimise = op match
        {
            case _: Pure[A => A] => throw new Exception("right chain given parser which consumes no input")
            case _: MZero => p
            case _ => this
        }
        override def codeGen(cont: =>Continuation)(implicit instrs: InstrBuffer, state: CodeGenState) =
        {
            val body = state.freshLabel()
            val handler = state.freshLabel()
            instrs += new instructions.InputCheck(handler)
            instrs += new instructions.Label(body)
            new Suspended(op.codeGen
            {
                instrs += new instructions.Label(handler)
                instrs += new instructions.ChainPre(body)
                p.codeGen
                {
                    instrs += instructions.Apply
                    cont
                }
            })
        }
    }
    private [parsley] final class Chainl[A](_p: =>Parsley[A], _op: =>Parsley[(A, A) => A]) extends Parsley[A]
    {
        private [Chainl] var p: Parsley[A] = _
        private [Chainl] var op: Parsley[(A, A) => A] = _
        override def preprocess(cont: Parsley[A] => Bounce[Parsley[_]])(implicit seen: Set[Parsley[_]], label: UnsafeOption[String], depth: Int) =
            if (label == null && p != null) cont(this) else _p.optimised(p => _op.optimised(op =>
            {
                if (label == null)
                {
                    this.p = p
                    this.op = op
                    cont(this)
                }
                else cont(Chainl(p, op))
            }))
        override def codeGen(cont: =>Continuation)(implicit instrs: InstrBuffer, state: CodeGenState) =
        {
            val body = state.freshLabel()
            val handler = state.freshLabel()
            new Suspended(p.codeGen
            {
                instrs += new instructions.InputCheck(handler)
                instrs += new instructions.Label(body)
                op.codeGen(p.codeGen
                {
                    instrs += new instructions.Label(handler)
                    instrs += new instructions.Chainl(body)
                    cont
                })
            })
        }
    }
    private [parsley] final class Chainr[A](_p: =>Parsley[A], _op: =>Parsley[(A, A) => A]) extends Parsley[A]
    {
        private [Chainr] var p: Parsley[A] = _
        private [Chainr] var op: Parsley[(A, A) => A] = _
        override def preprocess(cont: Parsley[A] => Bounce[Parsley[_]])(implicit seen: Set[Parsley[_]], label: UnsafeOption[String], depth: Int) =
            if (label == null && p != null) cont(this) else _p.optimised(p => _op.optimised(op =>
            {
                if (label == null)
                {
                    this.p = p
                    this.op = op
                    cont(this)
                }
                else cont(Chainr(p, op))
            }))
        override def codeGen(cont: =>Continuation)(implicit instrs: InstrBuffer, state: CodeGenState) =
        {
            val body = state.freshLabel()
            val handler = state.freshLabel()
            instrs += new instructions.InputCheck(handler)
            instrs += new instructions.Label(body)
            new Suspended(p.codeGen
            {
                instrs += new instructions.InputCheck(handler)
                op.codeGen
                {
                    instrs += new instructions.Label(handler)
                    instrs += new instructions.Chainr(body)
                    cont
                }
            })
        }
    }
    private [parsley] final class SepEndBy1[A, B](_p: =>Parsley[A], _sep: =>Parsley[B]) extends Parsley[List[A]]
    {
        private [SepEndBy1] var p: Parsley[A] = _
        private [SepEndBy1] var sep: Parsley[B] = _
        override def preprocess(cont: Parsley[List[A]] => Bounce[Parsley[_]])(implicit seen: Set[Parsley[_]], label: UnsafeOption[String], depth: Int) =
            if (label == null && p != null) cont(this) else _p.optimised(p => _sep.optimised(sep =>
            {
                if (label == null)
                {
                    this.p = p
                    this.sep = sep
                    cont(this)
                }
                else cont(SepEndBy1(p, sep))
            }))
        override def codeGen(cont: =>Continuation)(implicit instrs: InstrBuffer, state: CodeGenState) =
        {
            val body = state.freshLabel()
            val handler = state.freshLabel()
            instrs += new instructions.InputCheck(handler)
            instrs += new instructions.Label(body)
            new Suspended(p.codeGen
            {
                instrs += new instructions.InputCheck(handler)
                sep.codeGen
                {
                    instrs += new instructions.Label(handler)
                    instrs += new instructions.SepEndBy1(body)
                    cont
                }
            })
        }
    }
    private [parsley] final class ManyUntil[A](_body: Parsley[Any]) extends Parsley[List[A]]
    {
        private [ManyUntil] var body: Parsley[Any] = _
        override def preprocess(cont: Parsley[List[A]] => Bounce[Parsley[_]])(implicit seen: Set[Parsley[_]], label: UnsafeOption[String], depth: Int) =
            if (label == null && body != null) cont(this) else _body.optimised(body =>
            {
                if (label == null)
                {
                    this.body = body
                    cont(this)
                }
                else cont(ManyUntil(body))
            })
        override def codeGen(cont: =>Continuation)(implicit instrs: InstrBuffer, state: CodeGenState) =
        {
            val start = state.freshLabel()
            val loop = state.freshLabel()
            instrs += new instructions.PushFallthrough(loop)
            instrs += new instructions.Label(start)
            new Suspended(body.codeGen
            {
                instrs += new instructions.Label(loop)
                instrs += new instructions.ManyUntil(start)
                cont
            })
        }
    }
    private [parsley] final class Ternary[A](_b: =>Parsley[Boolean], _p: =>Parsley[A], _q: =>Parsley[A]) extends Parsley[A]
    {
        private [Ternary] var b: Parsley[Boolean] = _
        private [Ternary] var p: Parsley[A] = _
        private [Ternary] var q: Parsley[A] = _
        override def preprocess(cont: Parsley[A] => Bounce[Parsley[_]])(implicit seen: Set[Parsley[_]], label: UnsafeOption[String], depth: Int) =
            if (label == null && b != null) cont(this) else _b.optimised(b => _p.optimised(p => _q.optimised(q =>
            {
                if (label == null)
                {
                    this.b = b
                    this.p = p
                    this.q = q
                    cont(this)
                }
                else cont(Ternary(b, p, q))
            })))
        override def optimise = b match
        {
            case Pure(true) => p
            case Pure(false) => q
            case _ => this
        }
        override def codeGen(cont: =>Continuation)(implicit instrs: InstrBuffer, state: CodeGenState) =
        {
            val success = state.freshLabel()
            val end = state.freshLabel()
            new Suspended(b.codeGen
            {
                instrs += new instructions.If(success)
                q.codeGen
                {
                    instrs += new instructions.Jump(end)
                    instrs += new instructions.Label(success)
                    p.codeGen
                    {
                        instrs += new instructions.Label(end)
                        cont
                    }
                }
            })
        }
    }
    private [parsley] final class NotFollowedBy[A](_p: =>Parsley[A], val expected: UnsafeOption[String] = null) extends Parsley[Nothing]
    {
        private [NotFollowedBy] var p: Parsley[A] = _
        override def preprocess(cont: Parsley[Nothing] => Bounce[Parsley[_]])(implicit seen: Set[Parsley[_]], label: UnsafeOption[String], depth: Int) =
            if (label == null && p != null) cont(this) else _p.optimised(p =>
            {
                if (label == null)
                {
                    this.p = p
                    cont(this)
                }
                else cont(NotFollowedBy(p, label))
            })
        override def codeGen(cont: =>Continuation)(implicit instrs: InstrBuffer, state: CodeGenState) =
        {
            val handler = state.freshLabel()
            instrs += new instructions.PushHandler(handler)
            p.codeGen
            {
                instrs += new instructions.Label(handler)
                instrs += new instructions.NotFollowedBy(expected)
                cont
            }
        }
    }
    private [parsley] final class Eof(val expected: UnsafeOption[String] = null) extends Parsley[Nothing]
    {
        override def preprocess(cont: Parsley[Nothing] => Bounce[Parsley[_]])(implicit seen: Set[Parsley[_]], label: UnsafeOption[String], depth: Int) =
        {
            if (label == null) cont(this)
            else cont(new Eof(label))
        }
        override def codeGen(cont: =>Continuation)(implicit instrs: InstrBuffer, state: CodeGenState) =
        {
            instrs += new instructions.Eof(expected)
            cont
        }
    }
    private [parsley] object Line extends Parsley[Int]
    {
        override def preprocess(cont: Parsley[Int] => Bounce[Parsley[_]])(implicit seen: Set[Parsley[_]], label: UnsafeOption[String], depth: Int) = cont(this)
        override def codeGen(cont: =>Continuation)(implicit instrs: InstrBuffer, state: CodeGenState) =
        {
            instrs += instructions.Line
            cont
        }
    }
    private [parsley] object Col extends Parsley[Int]
    {
        override def preprocess(cont: Parsley[Int] => Bounce[Parsley[_]])(implicit seen: Set[Parsley[_]], label: UnsafeOption[String], depth: Int) = cont(this)
        override def codeGen(cont: =>Continuation)(implicit instrs: InstrBuffer, state: CodeGenState) =
        {
            instrs += instructions.Col
            cont
        }
    }
    private [parsley] final class Get[S](v: Var) extends Parsley[S]
    {
        override def preprocess(cont: Parsley[S] => Bounce[Parsley[_]])(implicit seen: Set[Parsley[_]], label: UnsafeOption[String], depth: Int) = cont(this)
        override def codeGen(cont: =>Continuation)(implicit instrs: InstrBuffer, state: CodeGenState) =
        {
            instrs += new instructions.Get(v.v)
            cont
        }
    }
    private [parsley] final class Put[S](private [Put] val v: Var, _p: =>Parsley[S]) extends Parsley[Unit]
    {
        private [Put] var p: Parsley[S] = _
        override def preprocess(cont: Parsley[Unit] => Bounce[Parsley[_]])(implicit seen: Set[Parsley[_]], label: UnsafeOption[String], depth: Int) =
            if (label == null && p != null) cont(this) else _p.optimised(p =>
            {
                if (label == null)
                {
                    this.p = p
                    cont(this)
                }
                else cont(Put(v, p))
            })
        override def codeGen(cont: =>Continuation)(implicit instrs: InstrBuffer, state: CodeGenState) =
        {
            p.codeGen
            {
                instrs += new instructions.Put(v.v)
                cont
            }
        }
    }
    private [parsley] final class Modify[S](v: Var, f: S => S) extends Parsley[S]
    {
        override def preprocess(cont: Parsley[S] => Bounce[Parsley[_]])(implicit seen: Set[Parsley[_]], label: UnsafeOption[String], depth: Int) = cont(this)
        override def codeGen(cont: =>Continuation)(implicit instrs: InstrBuffer, state: CodeGenState) =
        {
            instrs += new instructions.Modify(v.v, f)
            cont
        }
    }
    private [parsley] final class Local[S, A](private [Local] val v: Var, _p: =>Parsley[S], _q: =>Parsley[A]) extends Parsley[A]
    {
        private [Local] var p: Parsley[S] = _
        private [Local] var q: Parsley[A] = _
        override def preprocess(cont: Parsley[A] => Bounce[Parsley[_]])(implicit seen: Set[Parsley[_]], label: UnsafeOption[String], depth: Int) =
            if (label == null && p != null) cont(this) else _p.optimised(p => _q.optimised(q =>
            {
                if (label == null)
                {
                    this.p = p
                    this.q = q
                    cont(this)
                }
                else cont(Local(v, p, q))
            }))
        override def codeGen(cont: =>Continuation)(implicit instrs: InstrBuffer, state: CodeGenState) =
        {
            p.codeGen
            {
                instrs += new instructions.LocalEntry(v.v)
                q.codeGen
                {
                    instrs += new instructions.LocalExit(v.v)
                    cont
                }
            }
        }
    }
    private [parsley] final class ErrorRelabel[+A](p: =>Parsley[A], msg: String) extends Parsley[A]
    {
        override def preprocess(cont: Parsley[A] => Bounce[Parsley[_]])(implicit seen: Set[Parsley[_]], label: UnsafeOption[String], depth: Int) =
        {
            if (label == null) p.optimised(cont)(seen, msg, depth)
            else p.optimised(cont)
        }
        override def optimise = throw new Exception("Error relabelling should not be in optimisation!")
        override def codeGen(cont: =>Continuation)(implicit instrs: InstrBuffer, state: CodeGenState) = throw new Exception("Error relabelling should not be in code gen!")
    }
    private [parsley] final class Debug[A](_p: =>Parsley[A], name: String, break: Breakpoint) extends Parsley[A]
    {
        private [Debug] var p: Parsley[A] = _
        override def preprocess(cont: Parsley[A] => Bounce[Parsley[_]])(implicit seen: Set[Parsley[_]], label: UnsafeOption[String], depth: Int) =
            if (label == null && p != null) cont(this) else _p.optimised(p =>
            {
                if (label == null)
                {
                    this.p = p
                    cont(this)
                }
                else cont(Debug(p, name, break))
            })
        override def codeGen(cont: =>Continuation)(implicit instrs: InstrBuffer, state: CodeGenState) =
        {
            val handler = state.freshLabel()
            instrs += new instructions.LogBegin(handler, name, (break eq EntryBreak) || (break eq FullBreak))
            p.codeGen
            {
                instrs += new instructions.Label(handler)
                instrs += new instructions.LogEnd(name, (break eq ExitBreak) || (break eq FullBreak))
                cont
            }
        }
    }

    private [DeepEmbedding] object Pure
    {
        def unapply[A](self: Pure[A]): Option[A] = Some(self.x)
    }
    private [DeepEmbedding] object <*>
    {
        def apply[A, B](pf: Parsley[A=>B], px: Parsley[A]): <*>[A, B] =
        {
            val res: <*>[A, B] = new <*>(pf, px)
            res.pf = pf
            res.px = px
            res
        }
        def unapply[A, B](self: <*>[A, B]): Option[(Parsley[A=>B], Parsley[A])] = Some((self.pf, self.px))
    }
    private [DeepEmbedding] object <|>
    {
        def apply[A, B](p: Parsley[A], q: Parsley[B]): A <|> B =
        {
            val res: A <|> B = new <|>(p, q)
            res.p = p
            res.q = q
            res
        }
        def unapply[A, B](self: A <|> B): Option[(Parsley[A], Parsley[B])] = Some((self.p, self.q))
    }
    private [DeepEmbedding] object >>=
    {
        def apply[A, B](p: Parsley[A], f: A => Parsley[B], expected: UnsafeOption[String]): >>=[A, B] =
        {
            val res: >>=[A, B] = new >>=(p, f, expected)
            res.p = p
            res
        }
        def unapply[A, B](self: >>=[A, B]): Option[(Parsley[A], A => Parsley[B])] = Some((self.p, self.f))
    }
    private [DeepEmbedding] object Cont
    {
        def unapply[A, B](self: Cont[A, B]): Option[(Parsley[A], Parsley[B])] = Some((self.discard, self.result))
    }
    private [DeepEmbedding] object *>
    {
        def apply[A, B](p: Parsley[A], q: Parsley[B]): A *> B =
        {
            val res: A *> B = new *>(p, q)
            res.p = p
            res.q = q
            res
        }
        def unapply[A, B](self: A *> B): Option[(Parsley[A], Parsley[B])] = Some((self.p, self.q))
    }
    private [DeepEmbedding] object <*
    {
        def apply[A, B](p: Parsley[A], q: Parsley[B]): A <* B =
        {
            val res: A <* B = new <*(p, q)
            res.p = p
            res.q = q
            res
        }
        def unapply[A, B](self: A <* B): Option[(Parsley[A], Parsley[B])] = Some((self.p, self.q))
    }
    private [DeepEmbedding] object Attempt
    {
        def apply[A](p: Parsley[A]): Attempt[A] =
        {
            val res: Attempt[A] = new Attempt(p)
            res.p = p
            res
        }
        def unapply[A](self: Attempt[A]): Option[Parsley[A]] = Some(self.p)
    }
    private [DeepEmbedding] object Look
    {
        def apply[A](p: Parsley[A]): Look[A] =
        {
            val res: Look[A] = new Look(p)
            res.p = p
            res
        }
    }
    private [DeepEmbedding] object Subroutine
    {
        def apply[A](p: Parsley[A], expected: UnsafeOption[String]): Subroutine[A] =
        {
            val res: Subroutine[A] = new Subroutine(p, expected)
            res.p = p
            res
        }
    }
    private [DeepEmbedding] object CharTok
    {
        def unapply(self: CharTok): Option[Char] = Some(self.c)
    }
    private [DeepEmbedding] object StringTok
    {
        def unapply(self: StringTok): Option[String] = Some(self.s)
    }
    private [DeepEmbedding] object Satisfy
    {
        def unapply(self: Satisfy): Option[Char => Boolean] = Some(self.f)
    }
    private [DeepEmbedding] object Lift2
    {
        def apply[A, B, C](f: (A, B) => C, p: Parsley[A], q: Parsley[B]): Lift2[A, B, C] =
        {
            val res: Lift2[A, B, C] = new Lift2(f, p, q)
            res.p = p
            res.q = q
            res
        }
        def unapply[A, B, C](self: Lift2[A, B, C]): Option[((A, B) => C, Parsley[A], Parsley[B])] = Some((self.f, self.p, self.q))
    }
    private [DeepEmbedding] object Lift3
    {
        def apply[A, B, C, D](f: (A, B, C) => D, p: Parsley[A], q: Parsley[B], r: Parsley[C]): Lift3[A, B, C, D] =
        {
            val res: Lift3[A, B, C, D] = new Lift3(f, p, q, r)
            res.p = p
            res.q = q
            res.r = r
            res
        }
        def unapply[A, B, C, D](self: Lift3[A, B, C, D]): Option[((A, B, C) => D, Parsley[A], Parsley[B], Parsley[C])] = Some((self.f, self.p, self.q, self.r))
    }
    private [DeepEmbedding] object FastFail
    {
        def apply[A](p: Parsley[A], msggen: A => String, expected: UnsafeOption[String]): FastFail[A] =
        {
            val res: FastFail[A] = new FastFail(p, msggen, expected)
            res.p = p
            res
        }
    }
    private [DeepEmbedding] object FastUnexpected
    {
        def apply[A](p: Parsley[A], msggen: A => String, expected: UnsafeOption[String]): FastUnexpected[A] =
        {
            val res: FastUnexpected[A] = new FastUnexpected(p, msggen, expected)
            res.p = p
            res
        }
    }
    private [DeepEmbedding] object Ensure
    {
        def apply[A](p: Parsley[A], pred: A => Boolean, expected: UnsafeOption[String]): Ensure[A] =
        {
            val res: Ensure[A] = new Ensure(p, pred, expected)
            res.p = p
            res
        }
    }
    private [DeepEmbedding] object Guard
    {
        def apply[A](p: Parsley[A], pred: A => Boolean, msg: String, expected: UnsafeOption[String]): Guard[A] =
        {
            val res: Guard[A] = new Guard(p, pred, msg, expected)
            res.p = p
            res
        }
    }
    private [DeepEmbedding] object FastGuard
    {
        def apply[A](p: Parsley[A], pred: A => Boolean, msggen: A => String, expected: UnsafeOption[String]): FastGuard[A] =
        {
            val res: FastGuard[A] = new FastGuard(p, pred, msggen, expected)
            res.p = p
            res
        }
    }
    private [DeepEmbedding] object Many
    {
        def apply[A](p: Parsley[A]): Many[A] =
        {
            val res: Many[A] = new Many(p)
            res.p = p
            res
        }
    }
    private [DeepEmbedding] object SkipMany
    {
        def apply[A](p: Parsley[A]): SkipMany[A] =
        {
            val res: SkipMany[A] = new SkipMany(p)
            res.p = p
            res
        }
    }
    private [DeepEmbedding] object ChainPost
    {
        def apply[A](p: Parsley[A], op: Parsley[A => A]): ChainPost[A] =
        {
            val res: ChainPost[A] = new ChainPost(p, op)
            res.p = p
            res.op = op
            res
        }
    }
    private [DeepEmbedding] object ChainPre
    {
        def apply[A](p: Parsley[A], op: Parsley[A => A]): ChainPre[A] =
        {
            val res: ChainPre[A] = new ChainPre(p, op)
            res.p = p
            res.op = op
            res
        }
    }
    private [DeepEmbedding] object Chainl
    {
        def apply[A](p: Parsley[A], op: Parsley[(A, A) => A]): Chainl[A] =
        {
            val res: Chainl[A] = new Chainl(p, op)
            res.p = p
            res.op = op
            res
        }
    }
    private [DeepEmbedding] object Chainr
    {
        def apply[A](p: Parsley[A], op: Parsley[(A, A) => A]): Chainr[A] =
        {
            val res: Chainr[A] = new Chainr(p, op)
            res.p = p
            res.op = op
            res
        }
    }
    private [DeepEmbedding] object SepEndBy1
    {
        def apply[A, B](p: Parsley[A], sep: Parsley[B]): SepEndBy1[A, B] =
        {
            val res: SepEndBy1[A, B] = new SepEndBy1(p, sep)
            res.p = p
            res.sep = sep
            res
        }
    }
    private [parsley] object ManyUntil
    {
        object Stop
        def apply[A](body: Parsley[Any]): ManyUntil[A] =
        {
            val res: ManyUntil[A] = new ManyUntil(body)
            res.body = body
            res
        }
    }
    private [DeepEmbedding] object Ternary
    {
        def apply[A](b: Parsley[Boolean], p: Parsley[A], q: Parsley[A]): Ternary[A] =
        {
            val res: Ternary[A] = new Ternary(b, p, q)
            res.b = b
            res.p = p
            res.q = q
            res
        }
    }
    private [DeepEmbedding] object NotFollowedBy
    {
        def apply[A](p: Parsley[A], expected: UnsafeOption[String]): NotFollowedBy[A] =
        {
            val res: NotFollowedBy[A] = new NotFollowedBy(p, expected)
            res.p = p
            res
        }
    }
    private [DeepEmbedding] object Put
    {
        def apply[S](v: Var, p: Parsley[S]): Put[S] =
        {
            val res: Put[S] = new Put(v, p)
            res.p = p
            res
        }
    }
    private [DeepEmbedding] object Local
    {
        def apply[S, A](v: Var, p: Parsley[S], q: Parsley[A]): Local[S, A] =
        {
            val res: Local[S, A] = new Local[S, A](v, p, q)
            res.p = p
            res.q = q
            res
        }
    }
    private [DeepEmbedding] object Debug
    {
        def apply[A](p: Parsley[A], name: String, break: Breakpoint): Debug[A] =
        {
            val res: Debug[A] = new Debug(p, name, break)
            res.p = p
            res
        }
    }
}
