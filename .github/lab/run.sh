#!/usr/bin/env bash
ALL=http,sql,transaction,connection,exception,security,authorization,rest-client,cache,messaging,scheduled,log,mail,fault-tolerance,ai,lifecycle,gc,resources,app-event,websocket,orm
NORESAI=${ALL/,resources/}; NORESAI=${NORESAI/,ai/}
# Short warm-up (the CI benchmark's regime): the ceiling of removing the resources and span-owner work.
bash .github/lab/journal-lab.sh /tmp/lab-out/short 5 \
  "off=/tmp/base.jar:false" "on=/tmp/base.jar:true" "on-noresai=/tmp/base.jar:true:--bootui.runtime-journal.sources=$NORESAI"
# Steady state: the same JVMs warmed for 90 s rather than 10 s.
WARM=90 bash .github/lab/journal-lab.sh /tmp/lab-out/steady 5 "off=/tmp/base.jar:false" "on=/tmp/base.jar:true"
