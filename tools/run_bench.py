#!/usr/bin/env python3
"""Measure paired QuietFrame client launches on an RTX 2080 X11/XWayland desktop."""

import argparse
from contextlib import contextmanager
import csv
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import random
import re
import shutil
import signal
from statistics import median
import subprocess
import sys
import tempfile
import time
from uuid import uuid4
from zipfile import ZipFile, BadZipFile

import env as local_env

ROOT = Path(__file__).resolve().parents[1]
BENCH = ROOT / 'bench'
BUILD = BENCH / 'build'
GAME = BUILD / 'run'
sys.path.insert(0, str(BENCH / 'controller'))
from desktop import Desktop, Unsupported, probe
from telemetry import Sampler, METRICS, hardware, weighted

STACK = ('sodium','lithium','ferritecore','lightshade','swiftdraw','ticksight','luxstride')
PROFILES = {'baseline': (), 'quietframe': ('quietframe',), 'stack-baseline': STACK, 'stack': (*STACK,'quietframe')}
PAIRS = (('baseline','quietframe'), ('stack-baseline','stack'))
PHASES = ('focused','blur','hover','leave','minimized','restored','afk','wake')
EXPECTED = {
    'fabric-api': ('fabric-api-0.164.0+26.3.jar', '0.164.0+26.3', '5a75198715a50a86787658617b03b97da9936d32df04896748c12135ba709de3'),
    'sodium': ('sodium-fabric-0.9.3-beta.1+mc26.3.jar', '0.9.3-beta.1+mc26.3', '84cdb90d273aadbc00cddbcc82ede6e44fd33ac635f23c1d43fec28cc43b9bd6'),
    'lithium': ('lithium-fabric-0.26.2+mc26.3.jar','0.26.2+mc26.3','0be5b17ef9f6e9244cd07b24b227553a73aec0cf93158cf28099f3094c167f1b'),
    'ferritecore': ('ferritecore-9.0.0-fabric.jar','9.0.0','213966c72ed967acc7392beb28a866fba301ff56b9976c2e7801c2db7de6bf22'),
    'lightshade': ('lightshade-shader-loader-0.0.1.jar','0.0.1','3caf7826c1bd6473f7f3985ccef174779d372a350e7ef0b3fd9f810aab2ea6e8'),
}
for mod, digest in {
    'swiftdraw': 'd9b739d192231b36a2051253fa8560cf545497583f2a4d9e13d5ca637aa97de0',
    'ticksight': 'aa946e8d2954fd9d1416ee2e79963dbd060ff4a0d6659e1d6b2691e78a9edda3',
    'luxstride': 'f93d3686996058592e895b3260c05109cd1c3cef5b4caa3913be16e34536c771',
    'quietframe': None,
}.items():
    EXPECTED[mod] = (f'{mod}-0.1.0.jar', '0.1.0', digest)
SURFACE = re.compile(r'VK_(?:ERROR_|SUBOPTIMAL)|\bVUID-|SurfaceException|device lost|(?:vulkan|swapchain|surface).*(?:failed|fatal|invalidat(?:e loop|ion loop)|error\b)', re.I)


def write(path, data):
    path.write_text(json.dumps(data,indent=2,allow_nan=False)+'\n')


def read(path):
    return json.loads(path.read_text())


def rows(path):
    with path.open(newline='') as f:
        return list(csv.DictReader(f))


def table(path, records, cols):
    with path.open('w',newline='') as f:
        writer = csv.DictWriter(f,cols,extrasaction='ignore')
        writer.writeheader()
        writer.writerows(records)


def args_for(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--profiles', default=','.join(PROFILES), help='Comma-separated baseline,quietframe,stack-baseline,stack')
    p.add_argument('--phase-sec',type=int,default=20)
    p.add_argument('--reps',type=int,default=2)
    p.add_argument('--warmup-sec',type=int,default=20)
    p.add_argument('--skip-render-when-minimized',action='store_true',help='Exercise zero-render mode instead of the 1 FPS cap')
    p.add_argument('--timeout-sec',type=int,default=360,help='Maximum startup/fixture wait per client')
    p.add_argument('--out',type=Path,help='Create this new results directory')
    p.add_argument('--vulkan-desktop',action='store_true',help='Select NVIDIA ICD, preserve DISPLAY and use SDL X11')
    p.add_argument('--input-backend',choices=('auto','xtest','mutter'),default='auto')
    p.add_argument('--probe-desktop',action='store_true',help='Test real input, hover and minimize/restore using disposable windows and exit')
    p.add_argument('--java-home',type=Path)
    p.add_argument('--gradle-home',type=Path,default=Path(os.environ.get('GRADLE_USER_HOME') or Path.home()/'.gradle'))
    p.add_argument('--mods-dir',type=Path,help='Directory of distribution JARs')
    for mod in EXPECTED:
        p.add_argument('--'+mod+'-jar',type=Path)
    a=p.parse_args(argv)
    a.profiles=[part.strip() for part in a.profiles.split(',')]
    if any(v not in PROFILES for v in a.profiles) or len(set(a.profiles)) != len(a.profiles):
        p.error('Choose distinct profiles from '+','.join(PROFILES))
    if not 1 <= a.phase_sec <= 3600 or not 1 <= a.reps <= 100 or a.warmup_sec < 0 or a.timeout_sec < 1:
        p.error('Invalid duration or repetition count')
    return a


def desktop_backend(choice):
    available = {cmd:shutil.which(cmd) for cmd in ('xprop','xrandr','xdotool','wmctrl','nvidia-smi')}
    report={'display':os.environ.get('DISPLAY'),'session':os.environ.get('XDG_SESSION_TYPE'),'tools':available,
            'tkinter':importlib.util.find_spec('tkinter') is not None,'probes':{}}
    candidates=['xtest','mutter'] if choice=='auto' else [choice]
    selected=None
    for backend in candidates:
        try:
            result=probe(backend)
            report['probes'][backend]=result
            if result['keyDelivered'] and result['blurHover'] and result['restored']:
                selected=backend
                break
        except Exception as e:
            report['probes'][backend]={'error':str(e)}
    report['selected']=selected
    return selected,report


def artifacts(a):
    cache=local_env.prism_cache()
    client_hash = local_env.sha256(cache / local_env.GAME_JAR)
    if client_hash != '4508d006323f24fa02876310c192d739af56516eb259000ac50f0909a68c9a2d':
        raise ValueError('Prism Minecraft 26.3 client hash mismatch')
    dirs=[a.mods_dir, os.environ.get('QUIETFRAME_MODS'), ROOT/'libs',
          *sorted(cache.glob('instances/*/minecraft/mods')), *sorted(cache.glob('instances/*/.minecraft/mods'))]
    needed={'fabric-api',*(mod for profile in a.profiles for mod in PROFILES[profile])}
    result={}
    for mod in sorted(needed):
        name, version, pinned=EXPECTED[mod]
        override=getattr(a,mod.replace('-','_')+'_jar')
        dirs_for_mod=[p for p in dirs[:2] if p]
        if mod=='quietframe':
            dirs_for_mod.append(ROOT/'build')
        else:
            dirs_for_mod.append(ROOT.parent/mod/'build')
        dirs_for_mod+=dirs[2:]
        options=[override] if override else [Path(p)/name for p in dirs_for_mod if p]
        path=next((p.expanduser().resolve() for p in options if p.expanduser().is_file()),None)
        if not path:
            raise ValueError(f'Missing {name}. Supply --{mod}-jar or --mods-dir.')
        with ZipFile(path) as z:
            meta=json.loads(z.read('fabric.mod.json'))
        digest=local_env.sha256(path)
        if (meta['id'],meta['version'])!=(mod,version) or pinned and digest != pinned:
            raise ValueError(f'Distribution ID, version or hash mismatch in {path}')
        result[mod]={'path':str(path),'version':version,'sha256':digest}
    return result


@contextmanager
def game_directory():
    if GAME.exists() or GAME.is_symlink():
        raise ValueError(f'Benchmark game directory already exists: {GAME}')
    with tempfile.TemporaryDirectory(prefix='quietframe-game-') as tmp:
        GAME.symlink_to(tmp,target_is_directory=True)
        try:
            yield
        finally:
            GAME.unlink()


def stage(mods, afk, skip):
    (GAME/'mods').mkdir()
    for mod, entry in mods.items():
        src=Path(entry['path'])
        dest=GAME/'mods'/src.name
        shutil.copyfile(src,dest)
        if local_env.sha256(dest)!=entry['sha256']:
            raise ValueError(f'{mod} changed after discovery')
    (GAME/'config').mkdir()
    if 'lightshade' in mods:
        write(GAME/'config/lightshade.json',{'version':1,'enabled':True,'selectedPack':'daylight','packs':{}})
    if 'quietframe' in mods:
        write(GAME/'config/quietframe.json',dict(enabled=True,unfocused_fps=10,minimized_fps=1,
              afk_fps=15,afk_timeout_seconds=afk,afk_enabled=True,hover_wakeup=True,
              skip_render_when_minimized=skip,audio_attenuation=True,unfocused_volume_multiplier=.25,
              minimized_volume_multiplier=0,show_status_badge=False,pause_on_lost_focus_override=False))
    options={'overrideWidth':1280,'overrideHeight':720,'fullscreen':'false','enableVsync':'false',
             'maxFps':260,'pauseOnLostFocus':'false','guiScale':2,'lang':'en_us','onboardAccessibility':'false',
             'tutorialStep':'none','showSubtitles':'false','bobView':'false'}
    (GAME/'options.txt').write_text(''.join(f'{k}:{v}\n' for k,v in options.items()))


class Protocol:
    def __init__(self,path,nonce,proc):
        self.path,self.nonce,self.proc=path,nonce,proc
        self.seq=0

    def wait(self,filename,timeout,predicate=lambda value:True,optional=False):
        end=time.monotonic()+timeout
        value=None
        path=self.path/filename
        while time.monotonic()<end:
            if path.is_file():
                value=read(path)
                if predicate(value):
                    return value
            if (self.path/'error.txt').exists():
                raise ValueError((self.path/'error.txt').read_text())
            if self.proc.poll() is not None:
                raise ValueError(f'Client exited {self.proc.returncode} waiting for {filename}')
            time.sleep(.04)
        msg=f'Timed out waiting for {filename}; last observation {value}'
        if optional:
            raise Unsupported(msg)
        raise ValueError(msg)

    def command(self,op,timeout=12,**args):
        self.seq+=1
        pending=self.path/'request.tmp'
        write(pending,dict(nonce=self.nonce,seq=self.seq,op=op,**args))
        pending.replace(self.path/'command.json')
        return self.wait(f'ack-{self.seq}.json', timeout,
                         lambda v:v['nonce']==self.nonce and v['seq']==self.seq)


def matches(name,s):
    if not s['playing']:
        return False
    if name=='minimized':
        return s['iconified']
    if s['iconified'] or (s['width'],s['height'])!=(1280,720):
        return False
    if name in ('blur','hover','leave'):
        return not s['focused'] and s['hovered']==(name=='hover')
    return s['focused']


def focus_and_key(d,io,game):
    d.activate(game)
    d.point(game)
    state=io.wait('status.json',6,lambda s: matches('focused',s),optional=True)
    last=state['lastInputMs']
    d.key(game)
    return io.wait('status.json',5,lambda s: matches('focused',s) and s['lastInputMs']>last
                   and s['idleMs']<1200,optional=True)


def drive(a,d,io,game,afk):
    io.wait('status.json',a.timeout_sec)
    sink=d.place_sink(game)
    pings=[]
    for _ in range(4):
        t0=time.monotonic_ns()
        ns=io.command('ping')['ns']
        t1=time.monotonic_ns()
        pings.append((t1-t0,(t0+t1)//2-ns))
    uncertainty,offset=min(pings)
    write(io.path/'clock.json',dict(hostMinusJvmNs=offset,uncertaintyNs=uncertainty//2))
    focus_and_key(d,io,game)
    time.sleep(a.warmup_sec)
    transitions={}
    for phase in PHASES:
        t0=time.monotonic_ns()
        try:
            if phase in ('focused','restored','afk'):
                focus_and_key(d,io,game)
            elif phase=='blur':
                d.activate(sink)
                io.wait('status.json', 5, lambda s: not s['focused'] and not s['iconified'], optional=True)
                # Relative mouse mode may release after the WM changes keyboard focus.
                # Cross the actual client edge after SDL has observed the focus transition.
                d.move_over(game)
                d.move_over(sink)
            elif phase in ('hover','leave'):
                if d.active()!=sink:
                    raise Unsupported('Focus sink lost keyboard focus')
                d.move_over(game if phase=='hover' else sink)
            elif phase=='minimized':
                d.minimize(game)
            elif phase=='wake':
                if 'unsupported' in transitions['afk']:
                    raise Unsupported('AFK state was not established before wake')
                last=read(io.path/'status.json')['lastInputMs']
                d.key(game)
            def correct(s):
                if s['ns']+offset < t0 or not matches(phase,s):
                    return False
                if phase=='afk':
                    return s['idleMs'] >= afk*1000+250
                if phase=='wake':
                    return s['lastInputMs']>last and s['idleMs']<1500 and s['lastPresentNs']+offset>=t0
                return True
            state=io.wait('status.json',afk+8 if phase=='afk' else 6,correct,optional=True)
            transitions[phase]=dict(injectedHostNs=t0,observed=state)
            if phase=='focused':
                io.command('capture',name='before')
            elif phase=='restored':
                io.command('capture',name='after')
            elif phase=='blur':
                io.command('save')
            io.command('phase',name=phase,seconds=a.phase_sec,timeout=a.phase_sec+8)
        except Unsupported as e:
            transitions[phase]=dict(injectedHostNs=t0,unsupported=str(e))
        write(io.path/'transitions.json',transitions)
    return offset,transitions


def pixel_diff(a,b,heatmap):
    from PIL import Image,ImageChops
    if not a.is_file() or not b.is_file():
        return {'exact':False,'error':'Missing focused capture'}
    with Image.open(a) as ai, Image.open(b) as bi:
        left,right=ai.convert('RGBA'),bi.convert('RGBA')
    result={'before':str(a),'after':str(b),'beforeSize':left.size,'afterSize':right.size,
            'beforeRGBA':hashlib.sha256(left.tobytes()).hexdigest(),'afterRGBA':hashlib.sha256(right.tobytes()).hexdigest()}
    if left.size!=right.size:
        return dict(result,exact=False,changedPixels=None)
    r,g,b,alpha=ImageChops.difference(left,right).split()
    diff=ImageChops.lighter(ImageChops.lighter(r,g),ImageChops.lighter(b,alpha))
    changed=left.width*left.height-diff.histogram()[0]
    if changed:
        diff.point(lambda x:min(255,x*8)).save(heatmap)
        result['heatmap']=str(heatmap)
    return dict(result,exact=changed==0 and left.size==(1280,720),changedPixels=changed,maxChannelError=diff.getextrema()[1])


def score(path, profile, rep, offset, transitions, afk, skip):
    phases=[]
    power=rows(path/'telemetry.csv')
    baseline_gain=None
    canonical = read(path/'options-hashes.json')['canonical']
    for name in PHASES:
        shared=dict(profile=profile,repetition=rep,phase=name)
        if 'unsupported' in transitions[name]:
            phases.append(dict(shared,status='unsupported',issues=transitions[name]['unsupported']))
            continue
        phase=read(path/(name+'.json'))
        ticks=rows(path/(name+'-ticks.csv'))
        issues=[]
        phase.update(weighted(power,phase['startNs']+offset,phase['endNs']+offset))
        samples=[dict(focused=t['focused']=='true',iconified=t['iconified']=='true',hovered=t['hovered']=='true',
                      width=int(t['width']),height=int(t['height']),playing=t['playing']=='true') for t in ticks]
        if phase['optionsSha256'] != canonical:
            issues.append('options bytes changed during gameplay')
        if not samples or not all(matches(name,s) for s in samples):
            issues.append('SDL state or gameplay changed')
        if any(phase[k] is None for k in METRICS):
            issues.append('telemetry coverage incomplete')
        if phase['tps'] < 19.5 or phase['worstTickGapMs']>=150:
            issues.append('client tick continuity')
        if phase['suboptimalPresents']:
            issues.append('suboptimal surface')
        gains=[float(t['gain']) for t in ticks if t['gain']]
        if name=='focused' and gains:
            baseline_gain=gains[0]
        treatment='quietframe' in PROFILES[profile]
        factor=0 if name=='minimized' else .25 if name in ('blur','hover','leave') else 1
        expected=baseline_gain*(factor if treatment else 1) if baseline_gain is not None else None
        phase['gainMin']=min(gains,default=None)
        phase['gainMax']=max(gains,default=None)
        if (not baseline_gain or len(gains)!=len(ticks) or not ticks or any(g!=expected for g in gains)
                or any(t['soundPlaying']!='true' for t in ticks)):
            issues.append('live listener gain or looping sound')
        if treatment and name in ('blur','leave') and not 9.5<=phase['fps']<=10.5:
            issues.append('10 FPS background target')
        seconds=(phase['endNs']-phase['startNs'])/1e9
        if treatment and name=='minimized' and phase['renderAttempts']>(0 if skip else seconds+1):
            issues.append('minimized render budget')
        if name in ('hover','restored','wake') and treatment and phases[0].get('fps',0)>20 and phase['fps']<=16:
            issues.append('visible frame rate stayed within the configured background caps')
        if name!='minimized' and not phase['presents']:
            issues.append('no visible presents')
        if name=='afk' and (any(int(t['idleMs'])<afk*1000 for t in ticks)
                             or treatment and abs(phase['fps']-15) > max(1,1/seconds)):
            issues.append('AFK cadence or input')
        phase.update(shared,status='failed' if issues else 'ok',issues=' | '.join(issues))
        phases.append(phase)
    return phases


def terminate(proc):
    if proc.poll() is not None:
        return
    os.killpg(proc.pid,signal.SIGTERM)
    try:
        proc.wait(timeout=8)
    except subprocess.TimeoutExpired:
        os.killpg(proc.pid,signal.SIGKILL)
        proc.wait()


def launch(a,env,files,gpu,backend,profile,rep,path):
    nonce=uuid4().hex
    mods={m:files[m] for m in ('fabric-api',*PROFILES[profile])}
    afk=4*a.phase_sec+15
    cmd=['./gradlew','--offline','--no-daemon','runClient',f'-PbenchOut={path}',f'-PbenchNonce={nonce}',f'-PbenchProfile={profile}']
    if 'lightshade' in mods:
        cmd.append('-PbenchShaderJar='+mods['lightshade']['path'])
    observer=hashlib.sha256()
    for p in sorted([*BENCH.joinpath('src').rglob('*'),BENCH/'build.gradle',BENCH/'gradle.properties']):
        if p.is_file():
            observer.update(str(p.relative_to(BENCH)).encode()+b'\0'+bytes.fromhex(local_env.sha256(p)))
    manifest=dict(profile=profile,repetition=rep,nonce=nonce,mods=mods,observerSha256=observer.hexdigest(),
                  afkSeconds=afk,gpu=gpu,phaseSeconds=a.phase_sec,skipRender=a.skip_render_when_minimized,
                  session=os.environ.get('XDG_SESSION_TYPE'),inputBackend=backend,
                  environment={k:env.get(k) for k in ('DISPLAY','SDL_VIDEO_DRIVER','VK_DRIVER_FILES','JAVA_HOME','GRADLE_USER_HOME')})
    write(path/'manifest.json',manifest)
    desktop=Desktop(backend)
    try:
        with game_directory(), (path/'gradle.log').open('wb') as log:
            stage(mods,afk,a.skip_render_when_minimized)
            proc=subprocess.Popen(cmd,cwd=BENCH,env=env,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
            sampler=None
            try:
                io=Protocol(path,nonce,proc)
                client=io.wait('client.json',a.timeout_sec)
                if client['nonce']!=nonce or client['sdl']!='x11' or not client['xid']:
                    raise ValueError('Client identity or SDL X11 window mismatch')
                if (any(client['mods'].get(m)!=entry['version'] for m,entry in mods.items())
                        or any(m in client['mods'] for m in EXPECTED.keys()-mods.keys())):
                    raise ValueError('Loaded mod set differs from staged profile')
                sampler=Sampler(client['pid'],gpu,path/'telemetry.csv')
                manifest.update(pid=client['pid'],starttime=sampler.starttime,clockTicksPerSecond=sampler.hz)
                write(path/'manifest.json',manifest)
                sampler.thread.start()
                offset,transitions=drive(a,desktop,io,client['xid'],afk)
                time.sleep(1.25)  # Obtain the sample covering the trailing phase boundary.
                sampler.close()
                if sampler.error:
                    raise ValueError(sampler.error)
                io.command('stop')
                if proc.wait(timeout=45):
                    raise ValueError('Loom client shutdown failed')
                shutil.copyfile(GAME/'options.txt',path/'options-exit.txt')
            finally:
                if sampler and sampler.thread.is_alive():
                    sampler.close()
                terminate(proc)
                if (GAME/'logs/latest.log').is_file():
                    shutil.copyfile(GAME/'logs/latest.log',path/'client.log')
                if (GAME/'crash-reports').is_dir():
                    shutil.copytree(GAME/'crash-reports',path/'crash-reports')
    finally:
        desktop.close()
    return afk, offset, transitions, client


def run_one(a,env,files,gpu,backend,profile,rep,path):
    path.mkdir(parents=True)
    # Avoid publishing status and collecting ticks on a potentially slow results volume.
    with tempfile.TemporaryDirectory(prefix='quietframe-observer-') as temporary:
        scratch = Path(temporary)
        print(f'Running repetition {rep}, {profile}. Live files {scratch}, archive {path}',flush=True)
        try:
            afk, offset, transitions, client = launch(a,env,files,gpu,backend,profile,rep,scratch)
        finally:
            shutil.copytree(scratch, path, dirs_exist_ok=True)
            logs = [path / 'client.log', path / 'gradle.log']
            surface = list(dict.fromkeys(line for log in logs if log.exists()
                           for line in log.read_text(errors='replace').splitlines() if SURFACE.search(line)))
            write(path/'surface-events.json',surface)
    phases=score(path,profile,rep,offset,transitions,afk,a.skip_render_when_minimized)
    hashes=read(path/'options-hashes.json')
    hashes['exit']=local_env.sha256(path/'options-exit.txt')
    options_exact=set(hashes)=={'canonical','before-save','after-save','final','exit'} and len(set(hashes.values()))==1
    save_path = path/'save-state.json'
    save_samples = read(save_path) if save_path.is_file() else {}
    expected_gain = .25 if 'quietframe' in PROFILES[profile] else 1.0
    save_verified = (set(save_samples) == {'before','after'} and
                     all(matches('blur',s) and s['soundPlaying'] and s['gain'] == expected_gain for s in save_samples.values()))
    diff=pixel_diff(path/'before.png',path/'after.png',path/'recovery-diff.png')
    report=dict(phases=phases,optionsHashes=hashes,optionsExact=options_exact,saveStateVerified=save_verified,pixels=diff,
                surfaceEvents=surface,fixture=read(path/'fixture.json'),client=client)
    write(path/'result.json',report)
    return report


def aggregate(root,runs):
    fields=('fps','p95Ms','tps','worstTickGapMs','renderAttempts','gainMin','gainMax',*METRICS,'joules')
    summaries=[]; paired=[]; pixels=[]; hardware_rows=[]
    problems=[]
    for (rep,profile),(path,report) in runs.items():
        if 'error' in report:
            problems.append(f'{rep}/{profile}: '+report['error'])
            summaries.append(dict(repetition=rep,profile=profile,status='error',issues=report['error']))
        else:
            summaries+=report['phases']
            if not (report['optionsExact'] and report['saveStateVerified'] and report['pixels']['exact']) or report['surfaceEvents']:
                problems.append(f'{rep}/{profile}: options, pixels or surface verification failed')
            pixels.append(dict(repetition=rep,profile=profile,comparison='recovery',**report['pixels']))
        if (path/'telemetry.csv').exists():
            offset=read_clock(path) if 'phases' in report else 0
            for row in rows(path/'telemetry.csv'):
                phase=''
                if 'phases' in report:
                    ns=int(row['ns'])-offset
                    phase=next((p['phase'] for p in report['phases'] if p.get('startNs',0)<=ns<p.get('endNs',0)),'')
                hardware_rows.append(dict(row,repetition=rep,profile=profile,phase=phase))
    for rep in sorted({key[0] for key in runs}):
        for arm,candidate in PAIRS:
            if (rep,arm) not in runs or (rep,candidate) not in runs:
                continue
            ap,ar=runs[rep,arm]; bp,br=runs[rep,candidate]
            if 'error' in ar or 'error' in br:
                continue
            pair=arm+'_vs_'+candidate
            same=ar['fixture']==br['fixture'] and all(ar['client'][k]==br['client'][k] for k in ('sdl','device','driver','backend'))
            if not same:
                problems.append(f'{pair} repetition {rep}: fixture or adapter mismatch')
            for cap in ('before','after'):
                diff=pixel_diff(ap/(cap+'.png'),bp/(cap+'.png'),bp/(cap+'-paired-diff.png'))
                pixels.append(dict(repetition=rep,comparison=pair,capture=cap,**diff))
            for x,y in zip(ar['phases'],br['phases'],strict=True):
                valid=same and x['status']=='ok' and y['status']=='ok'
                for metric in fields:
                    before,after=x.get(metric),y.get(metric)
                    delta=after-before if before is not None and after is not None else None
                    paired.append(dict(repetition=rep,pair=pair,phase=x['phase'],metric=metric,valid=valid,
                                       control=before,treatment=after,delta=delta,
                                       percentDelta=100*delta/before if before and delta is not None else None))
    for profile, _ in PAIRS:
        first = runs.get((1,profile))
        if not first or 'error' in first[1]:
            continue
        for (rep, arm), (path, result) in runs.items():
            if arm != profile or rep == 1 or 'error' in result:
                continue
            for capture in ('before', 'after'):
                diff = pixel_diff(first[0]/(capture+'.png'), path/(capture+'.png'), path/(capture+'-repeat-diff.png'))
                pixels.append(dict(repetition=rep,controlRepetition=1,profile=arm,comparison='control-repeat',capture=capture,**diff))
    if any(p.get('status')!='ok' for p in summaries) or any(not d['exact'] for d in pixels):
        problems.append('One or more required phases or decoded RGBA comparisons failed')
    grouped={}
    for r in paired:
        if r['valid'] and r['percentDelta'] is not None:
            grouped.setdefault((r['pair'],r['phase'],r['metric']),[]).append(r['percentDelta'])
    estimates=[]
    rng=random.Random(263)
    for (pair,phase,metric),values in grouped.items():
        ci=None
        if len(values)>=2:
            boot=sorted(median(rng.choices(values,k=len(values))) for _ in range(1000))
            ci=[boot[24],boot[974]]
        estimate = dict(pair=pair,phase=phase,metric=metric,pairs=len(values),medianPercentDelta=median(values),
                        bootstrap95Percent=ci)
        if phase == 'focused' and metric == 'fps':
            estimate['noLossAssessment'] = ('insufficient pairs' if ci is None else
                'measured loss' if ci[1] < 0 else 'no measured loss' if ci[0] >= 0 else 'inconclusive')
        estimates.append(estimate)
    table(root/'summary.csv',summaries,('repetition','profile','phase','status','issues','startNs','endNs','ticks','presents','optionsSha256',*fields))
    table(root/'paired.csv',paired,('repetition','pair','phase','metric','valid','control','treatment','delta','percentDelta'))
    from telemetry import FIELDS
    table(root/'telemetry.csv',hardware_rows,('repetition','profile','phase',*FIELDS))
    write(root/'comparisons.json',dict(issues=problems,pixels=pixels,paired=paired,estimates=estimates))
    return problems


def read_clock(path):
    return read(path/'clock.json')['hostMinusJvmNs']


def benchmark(a):
    backend,probe_report=desktop_backend(a.input_backend)
    print(json.dumps(probe_report,indent=2),flush=True)
    if a.probe_desktop:
        return 0 if backend else 2
    if backend is None:
        raise ValueError('Desktop probe did not confirm all required states')
    import PIL.Image
    env=os.environ.copy()
    env['JAVA_HOME']=str((a.java_home or local_env.jdk()).expanduser().resolve())
    env['GRADLE_USER_HOME']=str(a.gradle_home.expanduser().resolve())
    env.pop('WAYLAND_DISPLAY',None)
    env.update(SDL_VIDEO_DRIVER='x11',SDL_VIDEODRIVER='x11')
    if a.vulkan_desktop:
        icd=Path('/usr/share/vulkan/icd.d/nvidia_icd.json')
        if not icd.exists():
            raise ValueError('NVIDIA Vulkan ICD missing')
        env['VK_DRIVER_FILES']=str(icd)
    gpu=hardware()
    files=artifacts(a)
    BUILD.mkdir(exist_ok=True)
    root=a.out.expanduser().resolve() if a.out else Path(tempfile.mkdtemp(dir=BUILD,prefix='results-'))
    if a.out:
        if root.is_relative_to(GAME):
            raise ValueError('Results must be outside the disposable game directory')
        root.mkdir(parents=True)
    write(root/'desktop.json',probe_report)
    runs={}
    for rep in range(1,a.reps+1):
        for pair in PAIRS:
            for profile in pair if rep%2 else reversed(pair):
                if profile not in a.profiles:
                    continue
                path=root/f'rep-{rep:02}'/profile
                try:
                    report=run_one(a,env,files,gpu,backend,profile,rep,path)
                except (OSError,Unsupported,ValueError,subprocess.SubprocessError) as e:
                    report={'error':str(e)}
                    write(path/'failure.json',report)
                    print(f'{profile} failed: {e}',file=sys.stderr,flush=True)
                runs[rep,profile]=path,report
                issues=aggregate(root,runs)
    print(f'Results: {root}',flush=True)
    return 2 if issues else 0


def main():
    args = args_for()
    BUILD.mkdir(exist_ok=True)
    with (BUILD / '.benchmark.lock').open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as exc:
            raise ValueError('Another QuietFrame probe or benchmark owns the desktop') from exc
        return benchmark(args)


if __name__=='__main__':
    try:
        raise SystemExit(main())
    except (OSError,ValueError,BadZipFile,subprocess.SubprocessError) as error:
        raise SystemExit(f'Benchmark failed: {error}')
