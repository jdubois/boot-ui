#!/usr/bin/env bash
WARM=90 PROFILE_VARIANTS="on on-opt" PROFILES="ctimer" bash .github/lab/journal-lab.sh /tmp/lab-out/opt 6 \
  "off=/tmp/base.jar:false" "on=/tmp/base.jar:true" "on-opt=/tmp/opt.jar:true"
