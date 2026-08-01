# ~/.bashrc  (RedTerm "DevOps" template)
# Git branch in prompt plus everyday dev aliases.

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

PROMPT_COMMAND='__rt_ret=$?'
PS1='\[\e[1;34m\]\w\[\e[0m\]$(__rt_git_branch)\[\e[1;36m\] (\$)\[\e[0m\] '

# git shortcuts
alias g='git'
alias ga='git add -A'
alias gc='git commit -m'
alias gcm='git commit -am'
alias gs='git status'
alias gp='git push'
alias gpl='git pull'
alias gl='git log --oneline --graph --decorate'
alias gd='git diff'
alias gco='git checkout'
alias gb='git branch -a'

# containers / services (only if installed)
command -v docker >/dev/null 2>&1 && alias dps='docker ps'
command -v docker >/dev/null 2>&1 && alias dlg='docker logs -f'
command -v kubectl >/dev/null 2>&1 && alias k='kubectl'
command -v systemctl >/dev/null 2>&1 && alias svc='systemctl status'

alias ls='ls --color=auto'
alias ll='ls -lah'
alias la='ls -A'
alias l='ls -CF'
alias grep='grep --color=auto'
alias ..='cd ..'
alias ...='cd ../..'
alias df='df -h'
alias du='du -h'
