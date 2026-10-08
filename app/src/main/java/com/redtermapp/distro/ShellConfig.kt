package com.redtermapp.distro

/**
 * The shell configuration the app writes into a distribution that has none of its own.
 *
 * A distribution that ships its own `.bashrc` is left completely alone — Debian, Alpine
 * and the rest know how to prompt on their own, and overwriting one is how a user loses
 * their customisation.
 */
object ShellConfig {

    /**
     * Marks a file as ours.
     *
     * Needed because the file is only written when missing, so a change here would
     * otherwise never reach an install that already has the old version. Writing
     * unconditionally is not the answer either: a hand-written `.bashrc` must survive.
     * This line is how the two are told apart — ours is ours to update, theirs is
     * theirs to keep.
     */
    const val MARKER = "# managed by RedTerm"

    /**
     * The prompt.
     *
     * **No colour escapes, deliberately.** The prompt used to hard-code bold green for
     * the user and bold blue for the path, and those are fixed palette indices: measured
     * against each theme's background, that blue runs from 1.06:1 on the dark themes —
     * effectively invisible — to 12.19:1 on the light one, and the green does the
     * opposite, dropping to 1.22:1 on light. A prompt that legible in one theme and gone
     * in the next is the prompt choosing a colour instead of inheriting one.
     *
     * Leaving the colour unset means the prompt draws in the terminal's default
     * foreground, which is the theme's `terminalText` — so it is legible in all ten
     * themes, it follows a live theme change with no edit to the file, and a theme added
     * later needs nothing here. Bold is kept for the path because bold resolves to the
     * bright slot, which follows the same foreground.
     *
     * Nothing here depends on `TERM` or on colour support: an uncoloured prompt is
     * readable in every terminal and every `TERM`, which is why it is the safe answer
     * rather than a harder one.
     */
    const val PS1 = """'\[\e[1m\]\u@\h\[\e[0m\]:\[\e[1m\]\w\[\e[0m\]\$ '"""

    /**
     * The whole file.
     *
     * Pure so its content can be asserted on. A prompt is a string in a shell file, and
     * nothing about a colour regression in one is visible without reading it.
     */
    fun bashrc(): String = """# ~/.bashrc
$MARKER
export TERM=xterm-256color
stty erase ^?
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
shopt -s histappend histreedit histverify checkwinsize cdspell dirspell
HISTSIZE=10000 HISTFILESIZE=20000
HISTCONTROL=ignoreboth:erasedups
# Quoted, because an unquoted '%T' is not a word here: bash reads a leading
# '%' as a job specifier and hands it to fg, which then reports
# "fg: no job control" on every login. It looked like a proot complaint and it
# was this line.
HISTTIMEFORMAT='%F %T '
# The prompt inherits the terminal's own foreground, so it follows whichever theme
# is selected. Hard-coded colours here are invisible on some of them and washed out
# on others, and they cannot follow a theme changed while the session is open.
PS1=$PS1
PROMPT_COMMAND='[ ${'$'}? -eq 0 ] || printf "\a"'
if [ -d /etc/bash_completion.d ]; then
    for f in /etc/bash_completion.d/*; do
        [ -f "${'$'}f" ] && . "${'$'}f"
    done
fi
alias ls='ls --color=auto'
alias ll='ls -lah'
alias la='ls -A'
alias l='ls -CF'
alias grep='grep --color=auto'
alias ..='cd ..'
alias ...='cd ../..'
alias rm='rm -i'
alias cp='cp -i'
alias mv='mv -i'
alias df='df -h'
alias du='du -h'
alias free='free -m'
alias vi='vim'
alias nano='nano -w'
"""

    /**
     * Whether [existing] is a file this app wrote, and so may be replaced.
     *
     * True when absent (nothing to protect), true when it carries our marker (ours to
     * keep up to date), false for anything else — which is the whole point: a user's
     * own `.bashrc` is never touched.
     */
    fun shouldWrite(existing: String?): Boolean =
        existing == null || MARKER in existing
}