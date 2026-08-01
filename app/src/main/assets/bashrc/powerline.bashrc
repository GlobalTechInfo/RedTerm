# ~/.bashrc  (RedTerm "Powerline" template)
# Colorful two-line prompt with git branch, exit status and clock.

__rt_git_branch() {
    command -v git >/dev/null 2>&1 || return 0
    local branch dirty
    branch=$(git rev-parse --abbrev-ref HEAD 2>/dev/null) || return 0
    dirty=$(git status --porcelain 2>/dev/null | head -1)
    if [ -n "$dirty" ]; then
        printf ' \033[1;31m(%s*)\033[0m' "$branch"
    else
        printf ' \033[1;32m(%s)\033[0m' "$branch"
    fi
}

__rt_status() {
    if [ "$1" -eq 0 ]; then
        printf '\033[1;32m✓\033[0m'
    else
        printf '\033[1;31m✗ %s\033[0m' "$1"
    fi
}

PROMPT_COMMAND='__rt_ret=$?'
PS1='\[\e[1;32m\]\u@\h\[\e[0m\]:\[\e[1;34m\]\w\[\e[0m\]$(__rt_git_branch) $(__rt_status "$__rt_ret") \[\e[1;36m\]\t\[\e[0m\]\n\$ '

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
