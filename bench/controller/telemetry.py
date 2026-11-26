"""Independently timed NVIDIA board and Linux client process measurements."""

import csv
import math
import os
from pathlib import Path
import subprocess
import threading
import time

GPU = ('gpuPercent', 'boardWatts', 'graphicsMHz', 'memoryMHz', 'temperatureC')
METRICS = (*GPU, 'cpuPercent')
FIELDS = ('prevNs', 'ns', 'queryMs', 'pid', 'starttime', *METRICS, 'error')


def smi(query, uuid=None):
    select = ['-i',uuid] if uuid else []
    output = subprocess.check_output(['nvidia-smi', *select, '--query-gpu='+query,
                                      '--format=csv,noheader,nounits'], timeout=4, text=True)
    return [[v.strip() for v in row] for row in csv.reader(output.splitlines())]


def hardware():
    gpus = [row for row in smi('name,uuid,driver_version,pci.bus_id') if 'RTX 2080' in row[0]]
    if len(gpus) != 1:
        raise ValueError('Exactly one RTX 2080 is required to associate board telemetry with the renderer')
    return dict(zip(('name','uuid','driver','pci'),gpus[0],strict=True))


def proc_stat(pid):
    stat = Path(f'/proc/{pid}/stat').read_text()
    tail = stat[stat.rindex(')')+1:].split()  # tail[0] is field 3.
    return int(tail[11])+int(tail[12]), int(tail[19])


class Sampler:
    def __init__(self, pid, gpu, path):
        self.pid, self.uuid, self.path = pid, gpu['uuid'], path
        self.hz = os.sysconf('SC_CLK_TCK')
        _, self.starttime = proc_stat(pid)
        self.stop = threading.Event()
        self.thread = threading.Thread(target=self.run, name='board-and-process-sampler')
        self.error = None

    def run(self):
        try:
            prev, identity = proc_stat(self.pid)
            if identity != self.starttime:
                raise RuntimeError('Client PID was reused before sampling')
            previous = time.monotonic_ns()
            deadline = previous + 1_000_000_000
            with self.path.open('w', newline='') as f:
                writer = csv.DictWriter(f,FIELDS)
                writer.writeheader()
                while not self.stop.wait(max(0, (deadline-time.monotonic_ns())/1e9)):
                    ticks, identity = proc_stat(self.pid)
                    now = time.monotonic_ns()
                    if identity != self.starttime:
                        raise RuntimeError('Client PID was reused during sampling')
                    elapsed = (now-previous)/1e9
                    row = dict(prevNs=previous, ns=now, pid=self.pid, starttime=identity,
                               cpuPercent=100*(ticks-prev)/(self.hz*elapsed))
                    try:
                        values, = smi('utilization.gpu,power.draw,clocks.current.graphics,clocks.current.memory,temperature.gpu', self.uuid)
                        for key,value in zip(GPU,values,strict=True):
                            try:
                                val = float(value)
                                row[key] = val if math.isfinite(val) else None
                            except ValueError:
                                row[key] = None
                        if any(row[k] is None for k in GPU):
                            row['error'] = 'unsupported GPU field'
                    except (OSError, subprocess.SubprocessError, ValueError) as e:
                        row['error'] = str(e)
                    row['queryMs'] = (time.monotonic_ns()-now)/1e6
                    writer.writerow(row)
                    f.flush()
                    prev, previous = ticks, now
                    deadline += 1_000_000_000
                    if deadline < time.monotonic_ns():
                        deadline = time.monotonic_ns()+1_000_000_000
        except Exception as e:
            self.error = str(e)

    def close(self):
        self.stop.set()
        self.thread.join()


def weighted(samples, start, end):
    # The CPU value averages its previous interval. Board readings use the same rectangular quadrature.
    totals = dict.fromkeys(METRICS, 0.0)
    coverage = dict.fromkeys(METRICS, 0)
    for row in samples:
        left,right = int(row['prevNs']), int(row['ns'])
        weight = min(end,right)-max(start,left)
        if weight <= 0 or right-left > 1_800_000_000:
            continue
        for key in METRICS:
            if row[key] != '' and (key == 'cpuPercent' or float(row['queryMs']) <= 500):
                totals[key] += weight*float(row[key])
                coverage[key] += weight
    result = {k: totals[k]/coverage[k] if coverage[k] == end-start else None for k in METRICS}
    result['joules'] = result['boardWatts']*(end-start)/1e9 if result['boardWatts'] is not None else None
    return result
