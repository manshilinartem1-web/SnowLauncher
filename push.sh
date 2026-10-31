#!/data/data/com.termux/files/usr/bin/bash
set -e
cd "$(dirname "$0")"

if ! command -v git >/dev/null 2>&1; then
  pkg install -y git
fi

read -p "GitHub username: " GH_USER
read -p "Имя репозитория (пустого, например SnowLauncher): " GH_REPO
read -p "Токен (ввод не виден): " GH_TOKEN
echo

if [ ! -d .git ]; then
  git init -b main
fi

git add -A
git -c user.name="$GH_USER" -c user.email="$GH_USER@users.noreply.github.com" commit -m "Snow launcher" || true

git push "https://$GH_USER:$GH_TOKEN@github.com/$GH_USER/$GH_REPO.git" HEAD:main

echo
echo "Готово! Открой https://github.com/$GH_USER/$GH_REPO и вкладку Actions"
