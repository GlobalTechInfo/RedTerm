# ~/.bashrc  (RedTerm "Retro CRT" template)
# Green-on-black console with a boot banner, date and time prompt.

__rt_ret=0
__rt_banner_shown=

__rt_banner() {
    printf '\033[1;32m'
    cat <<'EOF'
 ____    _____   ____    _____   _____   ____    __  __
|  _ \  | ____| |  _ \  |_   _| | ____| |  _ \  |  \/  |
| |_) | |  _|   | | | |   | |   |  _|   | |_) | | |\/| |
|  _ <  | |___  | |_| |   | |   | |___  |  _ <  | |  | |
|_| \_\ |_____| |____/    |_|   |_____| |_| \_\ |_|  |_|
EOF
    printf '\033[0m'
}

PROMPT_COMMAND='__rt_ret=$?; if [ -z "$__rt_banner_shown" ]; then __rt_banner; __rt_banner_shown=1; fi'
PS1='\[\e[1;32m\]\D{%a %b %d %T}\n\[\e[1;32m\]\w\[\e[0m\] \[\e[1;36m\]»\[\e[0m\] '

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
alias vi='vim'
