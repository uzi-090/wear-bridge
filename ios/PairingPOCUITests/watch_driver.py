import subprocess,xml.etree.ElementTree as E,re,time,sys,os
ADB=os.environ.get('ADB','adb')
SERIAL=os.environ['ANDROID_SERIAL']
def adb(*args): return subprocess.run([ADB,"-s",SERIAL,*args],capture_output=True,text=True,timeout=40)
def nodes():
 adb('shell','uiautomator','dump','--compressed','/sdcard/bridge-ui.xml')
 r=adb('shell','cat','/sdcard/bridge-ui.xml')
 return list(E.fromstring(r.stdout).iter('node'))
def tap(n):
 adb('shell','input','keyevent','KEYCODE_WAKEUP')
 b=list(map(int,re.findall(r'\d+',n.get('bounds'))));adb('shell','input','tap',str((b[0]+b[2])//2),str((b[1]+b[3])//2))
def click(text,scroll=0):
 labels=(text,) if isinstance(text,str) else text
 for i in range(scroll+1):
  ns=nodes()
  for n in ns:
   b=list(map(int,re.findall(r'\d+',n.get('bounds',''))))
   visible=len(b)==4 and b[2]>b[0] and b[3]>b[1] and 25<(b[1]+b[3])//2<415
   if visible and (n.get('text') in labels or n.get('content-desc') in labels):
    tap(n); print('WATCH_TAP:',text,flush=True); return True
  if i<scroll: adb('shell','input','swipe','225','350','225','130','350')
 print('WATCH_MISSING:',text,[(n.get('text'),n.get('content-desc')) for n in ns if n.get('text') or n.get('content-desc')],flush=True);return False
def begin_setup():
 home_device()
 for _ in range(3):
  ns=nodes()
  if any(n.get('content-desc','').startswith('Pairing code ') for n in ns):return
  if not any(n.get('text') in ('Pair iPhone','Pair again') for n in ns):return
  assert click(('Pair iPhone','Pair again'),1)
  time.sleep(1)
  result=adb('shell','run-as','io.wearbridge.watch','cat','shared_prefs/device_pairing.xml').stdout
  if 'PAIRING' in result or 'PAIRED' in result and 'UNPAIRED' not in result:return
 raise RuntimeError('Watch did not enter pairing after the action')
def home_device():
 adb('shell','input','keyevent','KEYCODE_WAKEUP')
 adb('shell','am','start','-n','io.wearbridge.watch/.ui.MainActivity','--es','route','device')
def state():
 ns=nodes(); print('WATCH_STATE:',[(n.get('text'),n.get('content-desc')) for n in ns if n.get('text') or n.get('content-desc')],flush=True)
def capture(name,large=False):
 folder=os.environ.get('QA_UI_DIR')
 if not folder:return
 from pathlib import Path
 path=Path(folder)/(name+'.png')
 if path.exists():return
 path.parent.mkdir(parents=True,exist_ok=True)
 adb('shell','input','keyevent','KEYCODE_WAKEUP')
 ns=nodes()
 with path.open('wb') as output:
  subprocess.run([ADB,'-s',SERIAL,'exec-out','screencap','-p'],stdout=output,timeout=15)
 (path.with_suffix('.xml')).write_text(adb('shell','cat','/sdcard/bridge-ui.xml').stdout)
 if large:
  saved=adb('shell','settings','get','system','font_scale').stdout.strip()
  try:
   adb('shell','settings','put','system','font_scale','1.3');time.sleep(2)
   with path.with_name(name+'-large.png').open('wb') as output:
    subprocess.run([ADB,'-s',SERIAL,'exec-out','screencap','-p'],stdout=output,timeout=15)
   nodes()
   path.with_name(name+'-large.xml').write_text(adb('shell','cat','/sdcard/bridge-ui.xml').stdout)
  finally:
   if saved=='null':adb('shell','settings','delete','system','font_scale')
   else:adb('shell','settings','put','system','font_scale',saved)
   time.sleep(2)
 print('WATCH_UI:',name,flush=True)
def remove_watch(forget, cancel_first=False):
 home_device(); title='Forget Device' if forget else 'Unpair iPhone'
 assert click(title,3)
 capture('forget-dialog' if forget else 'unpair-dialog',True)
 if cancel_first:
  assert click(('Dismiss','Cancel'),1)
  result=adb('shell','run-as','io.wearbridge.watch','cat','shared_prefs/device_pairing.xml').stdout
  assert 'PAIRED' in result and 'UNPAIRED' not in result,result
  print('WATCH_PASS: removal cancellation retained approval',flush=True)
  assert click(title,3)
 assert click(('Confirm',title,'Cancel pairing'),1)
 print('WATCH_REMOVED:',title,flush=True)
if sys.argv[1]=='state': state()
elif sys.argv[1]=='click': click(sys.argv[2],4)
elif sys.argv[1]=='device': home_device();state()
elif sys.argv[1]=='follow':
 log=sys.argv[2];seen=int(sys.argv[3]) if len(sys.argv)>3 else 0;deadline=time.time()+900;offline=False
 while time.time()<deadline:
  lines=open(log).read().splitlines()
  for line in lines[seen:]:
   if 'QA_WATCH: pair' in line:
    home_device()
    ns=nodes()
    if not any(n.get('content-desc','').startswith('Pairing code ') for n in ns):
     capture('saved-unpaired' if any(n.get('text')=='Pair again' for n in ns) else 'fresh-unpaired')
     begin_setup()
     capture('pairing-waiting')
   elif 'QA_WATCH: cancel_setup' in line:
    begin_setup()
    capture('waiting-saved')
    assert click('Cancel pairing',3)
    capture('cancel-setup-dialog')
    assert click(('Confirm','Cancel pairing'),2)
    time.sleep(2)
    result=adb('shell','run-as','io.wearbridge.watch','cat','shared_prefs/device_pairing.xml').stdout
    assert 'UNPAIRED' in result and 'name="address"' in result,result
    capture('cancelled-setup')
    print('WATCH_PASS: cancelled setup clears approval and retains the saved iPhone',flush=True)
   elif 'QA_WATCH: deny' in line:
    home_device();assert click('Deny',1)
    capture('pairing-denied')
   elif 'QA_WATCH: allow' in line:
    home_device()
    for _ in range(10):
     ns=nodes()
     codes=[re.sub(r'\D','',n.get('content-desc','')) for n in ns if n.get('content-desc','').startswith('Pairing code ')]
     codes += [re.sub(r'\D','',n.get('text','')) for n in ns if 'same code' in n.get('text','')]
     expected=[re.sub(r'\D','',l.split('QA_CODE:')[-1]) for l in lines[:seen]+lines[seen:] if 'QA_CODE:' in l]
     if codes and expected and codes[-1]!=expected[-1]: raise RuntimeError('Pairing codes do not match')
     buttons=[n for n in ns if n.get('text') in ('Allow iPhone','Allow')]
     if buttons and codes and expected and codes[-1]==expected[-1]:
      if 'QA_HOLD_SCREEN' in os.environ and not globals().get('held_screen_checked',False):
       time.sleep(18)
       power=adb('shell','dumpsys','power').stdout
       assert 'mWakefulness=Awake' in power, 'Approval screen went to sleep'
       window=adb('shell','dumpsys','window','windows').stdout
       assert 'KEEP_SCREEN_ON' in window, 'Approval screen did not request screen hold'
       held_screen_checked=True
       print('WATCH_PASS: code screen stays awake beyond the 15-second user timeout',flush=True)
      if 'QA_SCREENSHOT' in __import__('os').environ:
       with open(__import__('os').environ['QA_SCREENSHOT'],'wb') as output:
        subprocess.run([ADB,'-s',SERIAL,'exec-out','screencap','-p'],stdout=output,timeout=15)
       saved_scale=adb('shell','settings','get','system','font_scale').stdout.strip()
       try:
        adb('shell','settings','put','system','font_scale','1.3'); time.sleep(2)
        with open(__import__('os').environ['QA_SCREENSHOT'].replace('.png','-large-font.png'),'wb') as output:
         subprocess.run([ADB,'-s',SERIAL,'exec-out','screencap','-p'],stdout=output,timeout=15)
       finally:
        if saved_scale=='null': adb('shell','settings','delete','system','font_scale')
        else: adb('shell','settings','put','system','font_scale',saved_scale)
        time.sleep(2)
       ns=nodes(); buttons=[n for n in ns if n.get('text') in ('Allow','Allow iPhone')]
      tap(buttons[0]); print('WATCH_TAP: Allow iPhone (matching code)',flush=True); break
     time.sleep(2)
   elif 'QA_CHECK_WATCH:' in line:
    for _ in range(25):
     result=adb('shell','run-as','io.wearbridge.watch','cat','shared_prefs/device_pairing.xml').stdout
     if 'REMOVING' not in result:break
     time.sleep(.2)
    if not offline: assert 'UNPAIRED' in result,result
    if not offline and 'forgotten' in line: assert 'name="address"' not in result,result
    print('WATCH_STORE_PASS:',re.sub(r'([0-9A-F]{2}:){5}[0-9A-F]{2}','<peer>',result),flush=True)
    if offline:
     home_device();capture('offline-forgotten' if 'forgotten' in line else 'offline-unpaired')
   elif 'QA_WATCH: radio_on' in line:
    offline=False; adb('shell','svc','bluetooth','enable'); print('WATCH_RADIO: on',flush=True)
   elif 'QA_WATCH: offline_watch_' in line:
    offline=True; adb('shell','svc','bluetooth','disable'); home_device();capture('offline-device',True);remove_watch('offline_watch_forget' in line,True)
   elif 'QA_WATCH: radio_off' in line:
    offline=True; adb('shell','svc','bluetooth','disable'); print('WATCH_RADIO: off',flush=True)
    home_device();capture('bluetooth-off')
    adb('shell','input','keyevent','KEYCODE_WAKEUP')
    adb('shell','am','start','-n','io.wearbridge.watch/.ui.MainActivity','--es','route','media')
    ns=nodes(); assert not any('iPhone battery' in n.get('content-desc','') for n in ns)
    print('WATCH_PASS: media route gated during radio loss',flush=True)
   elif 'QA_WATCH: offline_cleanup_' in line:
    forget='offline_cleanup_forget' in line
    remove_watch(forget,True)
    offline=False; adb('shell','svc','bluetooth','enable'); print('WATCH_RADIO: on',flush=True)
   elif 'QA_WATCH: unpair' in line or 'QA_WATCH: forget' in line:
    forget='QA_WATCH: forget' in line
    remove_watch(forget,True)
   elif 'QA_WATCH: reconnect' in line: home_device();state()
  seen=len(lines)
  if '** TEST ' in '\n'.join(lines):break
  time.sleep(1)
