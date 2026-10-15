"""UI smoke helper. Targets emulator-5554 only; no desktop/phone input."""
import subprocess, re, xml.etree.ElementTree as ET, time, pathlib, sys, os, hashlib, tempfile
ADB=os.environ.get('ANDROID_HOME', str(pathlib.Path.home() / 'android')) + '/platform-tools/adb'
PROJECT = pathlib.Path(__file__).resolve().parents[1]
SERVER_APP = 'dev.cybercore.holodrive.photos.debug'
SCRATCH_APP = 'dev.cybercore.holodrive.photos.scratch.debug'
def adb(*args):
    return subprocess.check_output([ADB,'-s','emulator-5554',*args])
def nodes():
    adb('shell','uiautomator','dump','/sdcard/holodrive-ui.xml')
    return ET.fromstring(adb('shell','cat','/sdcard/holodrive-ui.xml')).iter('node')
def tap(label, attr='text'):
    for node in nodes():
        if node.get(attr, '').strip()==label.strip():
            x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
            adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
            time.sleep(.6)
            return
    raise Exception('Missing UI: '+label)
def screenshot(name):
    (PROJECT / 'docs' / 'screenshots' / (name+'.png')).write_bytes(adb('exec-out','screencap','-p'))
def dump():
    for n in nodes():
        if n.get('text') or n.get('content-desc'):
            print(n.get('text'),n.get('content-desc'),n.get('bounds'))
def login():
    base = pathlib.Path(pathlib.Path('/tmp/holodrive-photos-scratch-path').read_text())
    tap('Holodrive password')
    adb('shell','input','text',(base/'password').read_text())
    adb('shell','input','keyevent','4')
    tap('UNLOCK LIBRARY')
    time.sleep(2)

def assert_text(label):
    if not any(n.get('text') == label for n in nodes()):
        raise AssertionError('Missing text: ' + label)

def viewer_flow():
    tap('06 - Horizon étude.jpg', 'content-desc')
    screenshot('04-viewer')
    assert_text('1 / 6')
    # A single-finger swipe must page when the image is not zoomed.
    adb('shell','input','swipe','850','1150','180','1150','350')
    time.sleep(.8)
    assert_text('2 / 6')
    # Double-tap zoom, then reset it before saving.
    for _ in range(2):
        adb('shell','input','tap','540','1100')
    time.sleep(.5)
    screenshot('05-zoom')
    for _ in range(2):
        adb('shell','input','tap','540','1100')
    tap('Save original','content-desc')
    tap('SAVE')
    time.sleep(2)
    base = pathlib.Path(pathlib.Path('/tmp/holodrive-photos-scratch-path').read_text())
    expected = (base/'photos'/'05 - Horizon étude.jpg').read_bytes()
    with tempfile.TemporaryDirectory() as local:
        dest = pathlib.Path(local) / 'download.jpg'
        adb('pull','/sdcard/Download/05 - Horizon étude.jpg',str(dest))
        assert hashlib.sha256(expected).digest() == hashlib.sha256(dest.read_bytes()).digest(), 'Downloaded bytes differ'
    print('PASS: downloaded original matches source SHA-256')
    finish_viewer()

def finish_viewer():
    tap('Close photo','content-desc')
    tap('Empty album')
    assert_text('Room for memories')
    screenshot('06-empty-album')
    tap('Parent folder','content-desc')
    tap('Settings')
    screenshot('07-settings')

if __name__=='__main__':
    command = sys.argv[1] if len(sys.argv) > 1 else 'dump'
    if command == 'login': login()
    elif command == 'tap': tap(sys.argv[2], sys.argv[3] if len(sys.argv)>3 else 'text')
    elif command == 'screenshot': screenshot(sys.argv[2])
    elif command == 'install':
        apk = PROJECT/'app/build/outputs/apk/scratch/debug/app-scratch-debug.apk'
        adb('install','-r',str(apk))
        adb('shell','am','force-stop',SCRATCH_APP)
        adb('shell','am','start','-f','0x10008000','-n',SCRATCH_APP+'/dev.cybercore.holodrive.photos.MainActivity')
        time.sleep(2)
        # This is a synthetic-fixture-only build. Use its private scratch
        # credential so opening the test app never requires manual password
        # lookup or entry; production/debug builds retain normal sign-in.
        login()
        visible_text = {n.get('text', '').strip() for n in nodes()}
        if 'Photo library' in visible_text:
            tap('Photo library')
            visible_text = {n.get('text', '').strip() for n in nodes()}
            if 'USE THIS FOLDER' in visible_text:
                tap('USE THIS FOLDER')
                time.sleep(1)
    elif command == 'restart':
        adb('shell','am','start','-S','-f','0x10008000','-n',SCRATCH_APP+'/dev.cybercore.holodrive.photos.MainActivity')
        time.sleep(2)
    elif command == 'secure-check':
        from PIL import Image
        import io
        apk = PROJECT/'app/build/outputs/apk/debug/app-debug.apk'
        adb('install','-r',str(apk))
        adb('shell','am','start','-S','-f','0x10008000','-n',SERVER_APP+'/dev.cybercore.holodrive.photos.MainActivity')
        time.sleep(2)
        assert_text('Holodrive password')
        frame = Image.open(io.BytesIO(adb('exec-out','screencap','-p'))).convert('RGB')
        assert frame.crop((100,400,900,1800)).getbbox() is None, 'Secure window was visible in screenshot'
        print('PASS: normal APK blocks app-window screenshot capture')
    elif command == 'viewer': viewer_flow()
    elif command == 'finish-viewer':
        base = pathlib.Path(pathlib.Path('/tmp/holodrive-photos-scratch-path').read_text())
        with tempfile.TemporaryDirectory() as local:
            dest = pathlib.Path(local) / 'download.jpg'
            adb('pull','/sdcard/Download/05 - Horizon étude.jpg',str(dest))
            assert dest.read_bytes() == (base/'photos'/'05 - Horizon étude.jpg').read_bytes()
        print('PASS: downloaded original matches source bytes')
        finish_viewer()
    elif command == 'final-checks':
        assert_text('Your session expired. Sign in again.')
        screenshot('08-session-expired')
        login()
        tap('Folder')
        tap('Photo library')
        screenshot('02-folder-picker')
        tap('Weekend escapes')
        tap('USE THIS FOLDER')
        assert_text('2 photos · 0 albums')
        tap('Open Holodrive web GUI', 'content-desc')
        time.sleep(1)
        tasks = adb('shell','dumpsys','activity','activities').decode()
        assert 'com.android.chrome' in tasks and 'blackbox.tail9311b5.ts.net' in tasks
        print('PASS: full web GUI opened in external browser')
        adb('shell','am','start','-n',SCRATCH_APP+'/dev.cybercore.holodrive.photos.MainActivity')
        time.sleep(1)
        tap('Settings')
        adb('shell','input','swipe','550','1850','550','500','500')
        time.sleep(1)
        screenshot('07-settings')
        tap('Sign out')
        assert_text('Holodrive password')
        print('PASS: folder selection, session expiry and explicit sign-out')
    elif command == 'gallery':
        screenshot('01-login')
        login()
        tap('Photo library')
        screenshot('02-folder-picker')
        tap('USE THIS FOLDER')
        tap('Choose theme','content-desc')
        tap('CYBERGRID · cyan / pink')
        time.sleep(1)
        screenshot('03-gallery')
    elif command != 'dump': raise SystemExit('Unknown command')
    dump()
