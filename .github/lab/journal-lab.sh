#!/usr/bin/env bash
# Temporary measurement lab (not for merge): A/B/C of the Spring sample's jar variants on a quiet 4-CPU runner.
# usage: journal-lab.sh <out-dir> <passes> <variant>=<jar>:<journal true|false>[:extra args] ...
set -uo pipefail
OUT=$1; PASSES=$2; shift 2
mkdir -p "$OUT"
VARIANTS=("$@")
ASPROF=${ASPROF:-/tmp/async-profiler/bin/asprof}
JCMD="$JAVA_HOME/bin/jcmd"
port=19000
run_one() { # name jar journal extra profile(none|cpu|alloc|wall) label
  local name=$1 jar=$2 journal=$3 extra=$4 profile=$5 label=$6
  port=$((port+1))
  local d="$OUT/runs/$label"; mkdir -p "$d"
  java -Xms512m -Xmx512m -Xlog:gc:file="$d/gc.log":uptime -jar "$jar" --server.port=$port --spring.profiles.active=dev \
    "--spring.datasource.url=jdbc:h2:mem:lab_$port;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false" --bootui.show-banner=false \
    --bootui.overrides-file="$d/overrides.properties" --management.tracing.export.enabled=false --spring.jmx.enabled=false \
    --management.tracing.sampling.probability=1.0 --logging.level.root=WARN --logging.level.io.github.jdubois.bootui=WARN \
    --logging.level.org.hibernate.SQL=WARN --bootui.runtime-journal.enabled=$journal $extra > "$d/sample.log" 2>&1 &
  local pid=$!
  for i in $(seq 1 180); do curl -sf -o /dev/null "http://localhost:$port/bootui/api/overview" && break; sleep 1; done
  local url="http://127.0.0.1:$port/api/sample/product-search?term=console"
  ab -q -k -t ${WARM:-10} -n 100000000 -c 16 "$url" > "$d/warm.txt" 2>&1
  local t0; t0=$(tail -1 "$d/gc.log" | sed -E 's/^\[([0-9.]+)s.*/\1/')
  "$JCMD" $pid Thread.print > "$d/td0.txt" 2>/dev/null
  local c0; c0=$(awk '{print $14+$15}' /proc/$pid/stat)
  if [ "$profile" != none ]; then
    if [ "$profile" = alloc ]; then "$ASPROF" start -e alloc --total -t $pid >/dev/null; else "$ASPROF" start -e $profile -i 1ms -t $pid >/dev/null; fi
  fi
  ab -q -k -t 15 -n 100000000 -c 16 "$url" > "$d/ab.txt" 2>&1
  if [ "$profile" != none ]; then "$ASPROF" stop -t -o collapsed -f "$d/profile-$profile.collapsed" $pid >/dev/null; fi
  local c1; c1=$(awk '{print $14+$15}' /proc/$pid/stat)
  "$JCMD" $pid Thread.print > "$d/td1.txt" 2>/dev/null
  curl -s "http://localhost:$port/bootui/api/activity/journal" > "$d/journal.json"
  kill $pid; wait $pid 2>/dev/null
  python3 - "$d" "$name" "$label" "$t0" "$c0" "$c1" "$profile" >> "$OUT/results.tsv" <<'PY'
import sys,re,os,collections
d,name,label,t0,c0,c1,prof=sys.argv[1:]
ab=open(d+'/ab.txt').read()
req=int(re.search(r'Complete requests:\s+(\d+)',ab).group(1)); rps=float(re.search(r'Requests per second:\s+([\d.]+)',ab).group(1))
p99=re.search(r'\s99%\s+(\d+)',ab).group(1)
hz=os.sysconf('SC_CLK_TCK'); cpu=(int(c1)-int(c0))/hz
pause=0.0;n=0;conc=0;t0=float(t0 or 0)
for l in open(d+'/gc.log'):
    m=re.match(r'\[([\d.]+)s\]',l)
    if not m or float(m.group(1))<=t0: continue
    m2=re.search(r'Pause.* ([\d.]+)ms$',l.strip())
    if m2: pause+=float(m2.group(1)); n+=1
    if 'Concurrent Mark Cycle' in l and 'ms' in l: conc+=1
def td(f):
    r={}
    for l in open(f):
        m=re.match(r'^"(.+?)".*?cpu=([\d.]+)ms',l)
        if m: r[m.group(1)]=float(m.group(2))
    return r
a=td(d+'/td0.txt'); b=td(d+'/td1.txt'); g=collections.Counter()
for k,v in b.items(): g[re.sub(r'\d+','N',k)]+=v-a.get(k,0)
top=';'.join('%s=%.1f'%(k,1000*v/req) for k,v in g.most_common(8))
print('\t'.join([label,name,str(req),'%.0f'%rps,p99,'%.1f'%(1e6*cpu/req),'%.1f'%(1000*pause/req),str(n),str(conc),prof,top]))
PY
  tail -1 "$OUT/results.tsv"
}
printf 'label\tvariant\trequests\trps\tp99ms\tcpu_us_per_req\tgc_pause_us_per_req\tpauses\tconc_cycles\tprofile\tthreads_us_per_req\n' > "$OUT/results.tsv"
# Discarded warm-up of the load generator and the runner.
IFS='=' read -r n spec <<< "${VARIANTS[0]}"; IFS=':' read -r jar journal extra <<< "$spec"
run_one "$n" "$jar" "$journal" "${extra:-}" none "discard"
for pass in $(seq 1 "$PASSES"); do
  order=("${VARIANTS[@]}")
  if (( pass % 2 == 0 )); then order=(); for (( i=${#VARIANTS[@]}-1; i>=0; i-- )); do order+=("${VARIANTS[$i]}"); done; fi
  for v in "${order[@]}"; do
    IFS='=' read -r n spec <<< "$v"; IFS=':' read -r jar journal extra <<< "$spec"
    run_one "$n" "$jar" "$journal" "${extra:-}" none "$n-pass$pass"
  done
done
for prof in ${PROFILES:-}; do
  for v in "${VARIANTS[@]}"; do
    IFS='=' read -r n spec <<< "$v"; IFS=':' read -r jar journal extra <<< "$spec"
    if [ -n "${PROFILE_VARIANTS:-}" ] && [[ " $PROFILE_VARIANTS " != *" $n "* ]]; then continue; fi
    run_one "$n" "$jar" "$journal" "${extra:-}" "$prof" "$n-$prof"
  done
done
python3 - "$OUT/results.tsv" <<'PY' | tee "$OUT/summary.txt"
import sys,collections,statistics
rows=[l.rstrip('\n').split('\t') for l in open(sys.argv[1])][1:]
by=collections.defaultdict(list)
for r in rows:
    if r[9]=='none' and r[0]!='discard': by[r[1]].append(r)
for k,v in by.items():
    print('%-12s n=%d rps median %.0f  cpu-us/req median %.1f  gc-pause-us/req median %.1f  p99 median %s'%(k,len(v),statistics.median(float(x[3]) for x in v),statistics.median(float(x[5]) for x in v),statistics.median(float(x[6]) for x in v),statistics.median(int(x[4]) for x in v)))
names=list(by)
if len(names)>1:
    base=names[0]
    for other in names[1:]:
        ratios=[float(o[3])/float(b[3]) for b,o in zip(by[base],by[other])]
        print('%s vs %s: per-pass throughput ratio %s, median %.1f %%'%(other,base,', '.join('%.1f'%(100*r) for r in ratios),100*statistics.median(ratios)))
PY
