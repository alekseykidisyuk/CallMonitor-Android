"""Fail if delivery development alters the verified capture/recovery baseline."""
import subprocess
from pathlib import Path
BASE = '5031bbc76b67f60aa0ab3a46abe9608bb7db368b'
prefix = 'app/src/main/java/com/baba/callvault/'
protected = [prefix+'server', prefix+'integrations', prefix+'services/recording/AudioRecordingEngine.kt',
             '.github/scripts/build16_hyperos_ui_recovery_patch.py',
             '.github/scripts/build17_search_result_patch.py',
             '.github/scripts/build18_cleanup_ui_patch.py',
             '.github/scripts/build19_stereo_voicecall_patch.py', 'LICENSE']
subprocess.run(['git','diff','--exit-code',BASE,'--',*protected],check=True)
# The service change is exactly one post-close delivery hook.
service = prefix+'services/recording/RecordingForegroundService.kt'
before = subprocess.check_output(['git','show',BASE+':'+service],text=True)
marker = "        // Record this finished recording in CallVault's own catalog"
hook = '''        // CallMonitor: register the CLOSED file synchronously before service teardown.
        // Audio capture/encoder/release above are the verified build 19 baseline.
        com.baba.callvault.callmonitor.UploadScheduler.recordingClosed(applicationContext, uri, name)

'''
assert before.count(marker)==1
assert Path(service).read_text() == before.replace(marker,hook+marker), 'Recording service changed outside delivery hook'
print('PASS: capture, encode, recovery, existing patches and license unchanged; one post-close service hook.')
