#!/bin/sh
while IFS= read -r line; do
  case "$line" in
    uci) echo 'id name Fake UCI Test Engine'; echo 'uciok' ;;
    isready) echo 'readyok' ;;
    'go depth '*) echo 'info depth 16 score cp 42 pv e2e4 e7e5 g1f3'; echo 'bestmove e2e4 ponder e7e5' ;;
    quit) exit 0 ;;
  esac
done
