#!/usr/bin/env bash
ALL=http,sql,transaction,connection,exception,security,authorization,rest-client,cache,messaging,scheduled,log,mail,fault-tolerance,ai,lifecycle,gc,resources,app-event,websocket,orm
NORES=${ALL/,resources/}
WARM=90 PROFILE_VARIANTS="off on" PROFILES="ctimer" bash .github/lab/journal-lab.sh /tmp/lab-out/steady 3 \
  "off=/tmp/base.jar:false" "on=/tmp/base.jar:true" \
  "on-10k=/tmp/base.jar:true:--bootui.runtime-journal.max-events=10000" \
  "on-nores=/tmp/base.jar:true:--bootui.runtime-journal.sources=$NORES"
