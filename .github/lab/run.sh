#!/usr/bin/env bash
ALL=http,sql,transaction,connection,exception,security,authorization,rest-client,cache,messaging,scheduled,log,mail,fault-tolerance,ai,lifecycle,gc,resources,app-event,websocket,orm
NORES=${ALL/,resources/}
NOAI=${ALL/,ai/}
PROFILE_VARIANTS="off on" PROFILES="ctimer alloc" bash .github/lab/journal-lab.sh /tmp/lab-out 5 \
  "off=/tmp/base.jar:false" \
  "on=/tmp/base.jar:true" \
  "on-nores=/tmp/base.jar:true:--bootui.runtime-journal.sources=$NORES" \
  "on-noai=/tmp/base.jar:true:--bootui.runtime-journal.sources=$NOAI" \
  "on-min=/tmp/base.jar:true:--bootui.runtime-journal.sources=lifecycle" \
  "on-small=/tmp/base.jar:true:--bootui.runtime-journal.max-events=5000"
