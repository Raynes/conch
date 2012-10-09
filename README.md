# conch

[![Build Status](https://secure.travis-ci.org/Raynes/conch.png)](http://travis-ci.org/Raynes/conch)

Conch is a simple but very flexible Clojure library for shelling out to
external programs. It is to be used as an alternative to working
directly with the `java.lang.Process` API and as a more flexible
alternative to `clojure.java.shell`.

`clojure.java.shell` is designed to produce quick one-off processes.
There is no way to interact with a running process over time. I've found
myself needing to shell out and stream the output of an external process
to things in real time and I wasn't able to do that with
`clojure.java.shell`.

## Installation

In Leiningen:

```clojure
:dependencies [[conch "0.3.1"]]
```

## Usage

Conch is pretty simple. You spin off a process with `proc`.

```clojure
user=> (def p (sh/proc "cat"))
#'user/p
user=> p
{:out #<BufferedInputStream java.io.BufferedInputStream@5809fdee>, :in #<BufferedOutputStream java.io.BufferedOutputStream@5ebbfa5c>, :err #<DeferredCloseInputStream java.lang.UNIXProcess$DeferredCloseInputStream@58e5f46e>, :process #<UNIXProcess java.lang.UNIXProcess@18f42160>}
```

When you create a process with `proc`, you get back a map containing the
keys `:out`, `:err`, `:in`, and `:proc`.

* `:out` is the process's stdout.
* `:err` is the process's stderr.
* `:in` is the process's stdin.
* `:process` is the process object itself.

Conch is more flexible than `clojure.java.shell` because you have direct
access to all of the streams and the process object itself.

So, now we have a cat process. This is a unix tool. If you
run `cat` with no arguments, it echos whatever you type in. This makes it
perfect for testing input and output.

Conch defines a few utility functions for streaming output and feeding
input. Since we want to make sure that our input is going to the right
place, let's set up a way to see the output of our process in realtime:

```clojure
user=> (future (sh/stream-to-out p :out))
#<core$future_call$reify__5684@77b5c22f: :pending>
```

The `stream-to-out` function takes a process and either `:out` or `:err`
and streams that to `System/out`. In this case, it has the effect of printing
everything we pipe into our cat process, since our cat process just
outputs whatever we input.

```clojure
user=> (sh/feed-from-string p "foo\n")
nil
foo
```

The `feed-from-string` function just feeds a string to the process. It
automatically flushes (which is why this prints immediately) but you can
stop it from doing that by passing `:flush false`.

I think our cat process has lived long enough. Let's kill it and get its
exit code. We can use the `exit-code` function to get the exit code.
However, since `exit-code` stops the thread and waits for the process to
terminate, we should run it in a future until we actually destroy the
process.

```clojure
user=> (def exit (future (sh/exit-code p)))
#'user/exit
```

Now let's kill. R.I.P process.

```clojure
user=> (sh/destroy p)
nil
```

And the exit code, which we should be able to obtain now that the
process has been terminated:

```clojure
user=> @exit
0
```

Awesome! Let's go back to `proc` and see what else we can do with it. We
can pass multiple strings to `proc`. The first string will be considered
the executable and the rest of them the arguments to that executable.

```clojure
user=> (sh/proc "ls" "-l")
{:out #<BufferedInputStream java.io.BufferedInputStream@7fb6634c>, :in #<BufferedOutputStream java.io.BufferedOutputStream@1f315415>, :err #<DeferredCloseInputStream java.lang.UNIXProcess$DeferredCloseInputStream@5f873eb2>, :process #<UNIXProcess java.lang.UNIXProcess@2825491d>}
```

Here is an easy way to get the output of a one-off process like this as
a string:

```clojure
user=> (sh/stream-to-string (sh/proc "ls" "-l") :out)
"total 16\n-rw-r--r--  1 anthony  staff  2545 Jan 24 16:37 README.md\ndrwxr-xr-x  2 anthony  staff    68 Jan 19 19:23 classes\ndrwxr-xr-x  3 anthony  staff   102 Jan 19 19:23 lib\n-rw-r--r--  1 anthony  staff   120 Jan 20 14:45 project.clj\ndrwxr-xr-x  3 anthony  staff   102 Jan 20 14:45 src\ndrwxr-xr-x  3 anthony  staff   102 Jan 19 16:36 test\n"
```

Let's print that for readability:

```clojure
user=> (print (sh/stream-to-string (sh/proc "ls" "-l") :out))
total 16
-rw-r--r--  1 anthony  staff  2545 Jan 24 16:37 README.md
drwxr-xr-x  2 anthony  staff    68 Jan 19 19:23 classes
drwxr-xr-x  3 anthony  staff   102 Jan 19 19:23 lib
-rw-r--r--  1 anthony  staff   120 Jan 20 14:45 project.clj
drwxr-xr-x  3 anthony  staff   102 Jan 20 14:45 src
drwxr-xr-x  3 anthony  staff   102 Jan 19 16:36 test
nil
```

So, that's the `ls` of the current directory. I ran this REPL in the
conch project directory. We can, of course, pass a directory to `ls` to
get it to list the files in that directory, but that isn't any fun. We
can pass a directory to `proc` itself and it'll run in the context of
that directory.

```clojure
user=> (print (sh/stream-to-string (sh/proc "ls" "-l" :dir "lib/") :out))
total 6624
-rw-r--r--  1 anthony  staff  3390414 Jan 19 19:23 clojure-1.3.0.jar
nil
```

You can also pass a `java.io.File` or anything that can be passed to
`clojure.java.io/file`. 

We can also set environment variables:

```clojure
user=> (print (sh/stream-to-string (sh/proc "env" :env {"FOO" "bar"}) :out))
FOO=bar
nil
```

The map passed to `:env` completely replaces any other environment
variables that were in place.

Finally, there a couple of low-level functions for streaming from and
feeding to a process. They are `stream-to` and `feed-from`. These
functions are what the utility functions are built off of, and you can
probably use them to stream to and feed from your own special places.

You might want to fire off a program that listens for input until EOF.
In these cases, you can feed it data for as long as you want and just
tell it when you are done. Let's use `pygmentize` as an example:

```clojure
user=> (def proc (sh/proc "pygmentize" "-fhtml" "-lclojure"))
#'user/proc
user=> (sh/feed-from-string proc "(+ 3 3)")
nil
user=> (sh/done proc)
nil
user=> (sh/stream-to-string proc :out)
"<div class=\"highlight\"><pre><span class=\"p\">(</span><span class=\"nb\">+ </span><span class=\"mi\">3</span> <span class=\"mi\">3</span><span class=\"p\">)</span>\n</pre></div>\n"
```

When we call `done`, it closes the process's output stream which is
like sending EOF. The process processes its input and then puts it on
its input stream where we read it with `stream-to-string`.

### Other options

All of conch's streaming and feeding functions (including the lower
level ones) pass all of their keyword options to `clojure.java.io/copy`.
It can take an `:encoding` and `:buffer-size` option. Guess what they
do.

### Key names

You might notice that the map that `proc` returns is mapped like so:

* `:in`  -> output stream
* `:out` -> input stream

I did this because swapping them feels counterintuitive. The output
stream is what you put `:in` to and the input stream is what you pull
`:out` from.

## License

Copyright (C) 2012 Anthony Grimes

Distributed under the Eclipse Public License, the same as Clojure.
