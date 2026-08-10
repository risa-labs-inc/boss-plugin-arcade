package ai.rever.boss.plugin.dynamic.arcade.typingsprint

/**
 * Passage pool for the sprint. Plain ASCII, simple punctuation, roughly equal
 * difficulty — the clock and the typist are the variables, not the text.
 */
object TypingPassages {

    private val passages = listOf(
        "The quick brown fox jumps over the lazy dog while the calico cat " +
            "watches from the warm windowsill and refuses to be impressed.",
        "Good code reads like a well written letter. Every name says what it " +
            "means, every function does one thing, and nothing is left to guesswork.",
        "Ship early and ship often. A small improvement in the hands of real " +
            "users beats a perfect plan waiting quietly on a whiteboard.",
        "The standup ran long again because someone mentioned tabs versus " +
            "spaces, and now the whole team has strong opinions about indentation.",
        "A bug report without steps to reproduce is a treasure map without the " +
            "map. We know the gold exists, but nobody remembers which island.",
        "Coffee first, then email, then the hard problem you have been avoiding " +
            "since Tuesday. Momentum loves a small victory before lunch.",
        "The demo worked perfectly right until the projector turned on. Every " +
            "engineer in the room quietly checked the network settings twice.",
        "Documentation is a love letter to your future self, who will otherwise " +
            "spend a whole afternoon rediscovering what you already knew today.",
        "Naming things is hard, cache invalidation is harder, and estimating " +
            "how long either will take is the hardest problem of all.",
        "The keyboard is mightier than the mouse when the shortcuts are in your " +
            "fingers. Practice until your hands know the way without asking.",
        "Every large system that works started as a small system that worked. " +
            "Grow it patiently, measure it honestly, and prune it without mercy.",
        "Friday afternoon deploys are a courage test nobody assigned. The brave " +
            "click the button, and the wise schedule it for Monday morning.",
    )

    fun random(exclude: String? = null): String =
        passages.filter { it != exclude }.random()
}
