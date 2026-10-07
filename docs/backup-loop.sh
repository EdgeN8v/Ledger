#!/bin/sh
# Dump the database now, then every day at 03:00 UTC. Keeps the newest 14 dumps in /bak.
while true; do
  stamp=$(date -u +%Y%m%d-%H%M)
  if pg_dump "$DATABASE_URL" | gzip > /bak/ledger-$stamp.sql.gz.tmp && [ -s /bak/ledger-$stamp.sql.gz.tmp ]; then
    mv /bak/ledger-$stamp.sql.gz.tmp /bak/ledger-$stamp.sql.gz
    echo "$(date -u) dump ok ledger-$stamp.sql.gz" >> /bak/backup.log
  else
    rm -f /bak/ledger-$stamp.sql.gz.tmp
    echo "$(date -u) dump FAILED" >> /bak/backup.log
  fi
  ls -1t /bak/ledger-*.sql.gz 2>/dev/null | tail -n +15 | xargs -r rm -f
  now=$(( $(date -u +%H) * 3600 + $(date -u +%M) * 60 + $(date -u +%S) ))
  wait=$(( (3 * 3600 - now + 86400) % 86400 ))
  [ "$wait" -lt 600 ] && wait=$((wait + 86400))
  sleep "$wait"
done
