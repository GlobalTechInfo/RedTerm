# ~/.bashrc  (RedTerm "Minimal" template)
# Clean single-line prompt with a ✓/✗ status marker.

__rt_status() {
    if [ "$1" -eq 0 ]; then
        printf '\033[1;32m✓\033[0m'
    else
        printf '\033[1;31m✗\033[0m'
    fi
}

PROMPT_COMMAND='__rt_ret=$?'
PS1='\u@\h:\w $(__rt_status "$__rt_ret") \$ '

alias ls='ls --color=auto'
alias ll='ls -lah'
alias la='ls -A'
alias l='ls -CF'
alias grep='grep --color=auto'
alias ..='cd ..'
alias ...='cd ../..'
alias df='df -h'
alias du='du -h'
alias free='free -m'
