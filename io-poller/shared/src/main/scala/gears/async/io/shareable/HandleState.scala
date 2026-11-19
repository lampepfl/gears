package gears.async.io.shareable

import java.util.concurrent.atomic.AtomicLong

/** A handle's state and the work claimed on it, in one word, so that each changes with regard to the other in one step:
  * the state in the top byte, and in the 56 bits below it the count of the work now claimed.
  */
object HandleState:
  inline val UnenteredState = 0
  inline val OpenState = 1
  inline val ClosingState = 2
  inline val ClosedState = 3
  type State = UnenteredState.type | OpenState.type | ClosingState.type | ClosedState.type

  def showState(state: State): String =
    state match
      case UnenteredState => "Unentered"
      case OpenState      => "Open"
      case ClosingState   => "Closing"
      case ClosedState    => "Closed"

  private inline val StateShift = 56

  /** The most work that can be claimed at once, and the mask of its count in the word. */
  inline val MaxClaimed = 0x00ffffffffffffffL

  private inline def stateOf(word: Long): Int = (word >>> StateShift).toInt
  private inline def claimedOf(word: Long): Long = word & MaxClaimed
  private inline def wordOf(state: Int, claimed: Long): Long = (state.toLong << StateShift) | claimed

  abstract class Holder:
    private val word = AtomicLong(wordOf(UnenteredState, 0L))

    final def getState: State = stateOf(word.get()).asInstanceOf[State]

    /** How much work is claimed on the handle now. */
    final def claimedWork: Long = claimedOf(word.get())

    /** Moves from `expected` to `newState`, whatever work is claimed, which stays as it is. */
    final def compareAndSetState(expected: State, newState: State): Boolean =
      var result = false
      var done = false
      while !done do
        val current = word.get()
        if stateOf(current) != expected then done = true
        else if word.compareAndSet(current, wordOf(newState, claimedOf(current))) then
          result = true
          done = true
      result

    /** Claims work if the handle is open: the state it was found in, which is `OpenState` if the work was claimed. */
    protected final def claimIfOpen(): State =
      var found: Int = UnenteredState
      var done = false
      while !done do
        val current = word.get()
        found = stateOf(current)
        if found != OpenState then done = true
        else if claimedOf(current) == MaxClaimed then throw new IllegalStateException("Too much work claimed")
        else done = word.compareAndSet(current, current + 1)
      found.asInstanceOf[State]

    /** Gives back work claimed with `claimIfOpen`, which a handle that has begun to close still lets its work do:
      * whether that was the last of a closing handle's, for the caller then to close it.
      */
    protected final def releaseClaimed(): Boolean =
      var last = false
      var done = false
      while !done do
        val current = word.get()
        if claimedOf(current) == 0 then
          throw new IllegalStateException(s"Releasing work when none is claimed (${showState(getState)})")
        else if word.compareAndSet(current, current - 1) then
          last = stateOf(current) == ClosingState && claimedOf(current) == 1
          done = true
      last

    /** Begins the close of an open handle: the state it was found in, and, if that was `OpenState`, whether no work was
      * claimed, for the caller then to close it; otherwise the last work to be released does.
      */
    protected final def beginClose(): (found: State, idle: Boolean) =
      var found: Int = UnenteredState
      var idle = false
      var done = false
      while !done do
        val current = word.get()
        found = stateOf(current)
        if found != OpenState then done = true
        else if word.compareAndSet(current, wordOf(ClosingState, claimedOf(current))) then
          idle = claimedOf(current) == 0
          done = true
      (found.asInstanceOf[State], idle)
