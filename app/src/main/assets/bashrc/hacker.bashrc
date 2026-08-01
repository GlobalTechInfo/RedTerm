# ~/.bashrc  (RedTerm "Hacker" template)
# Green-on-black two-line prompt with ASCII frame.

__rt_status() {
    if [ "$1" -eq 0 ]; then
        echo -n "OK"
    else
        echo -n "FAIL($1)"
    fi
}

PROMPT_COMMAND='__rt_ret=$?'
PS1='\[\e[1;32m\]┌─(\[\e[0m\]\[\e[1;36m\]\u@\h\[\e[0m\]\[\e[1;32m\])─\[\e[1;32m\][\[\e[0m\]\[\e[1;33m\]\w\[\e[0m\]\[\e[1;32m\]]\n\[\e[1;32m\]└─\[\e[1;32m\]<\[\e[0m\]\[\e[1;37m\]$(__rt_status "$__rt_ret")\[\e[0m\]\[\e[1;32m\]> \$ \[\e[0m\]'

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
