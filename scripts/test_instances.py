import subprocess, time, os, sys, psutil

prism = r'F:\PrismLauncher-Windows\prismlauncher.exe'
instances = [
    '1.20.1_fab', '1.20.1_for', '1.20.1_neo',
    '1.21.1_fab', '1.21.1_for', '1.21.1_neo',
    '26.2_fab',   '26.2_for',   '26.2_neo'
]

def kill_mc_processes():
    for p in psutil.process_iter(['pid', 'name', 'cmdline']):
        try:
            name = (p.name() or '').lower()
            if 'java' in name or 'javaw' in name:
                cmd = ' '.join(p.cmdline() or [])
                if any(x in cmd for x in instances) or 'net.minecraft' in cmd or 'prism' in cmd.lower():
                    p.kill()
        except (psutil.NoSuchProcess, psutil.AccessDenied):
            pass

kill_mc_processes()
time.sleep(1)

results = {}

for inst in instances:
    print('========================================', flush=True)
    print(f'>>> Launching: {inst} ...', flush=True)
    print('========================================', flush=True)
    log_file = os.path.join(r'F:\PrismLauncher-Windows\instances', inst, 'minecraft', 'logs', 'latest.log')
    
    # Clean old log so we only read new lines
    if os.path.exists(log_file):
        try:
            with open(log_file, 'w') as f:
                f.write('')
        except Exception:
            pass
            
    start_time = time.time()
    subprocess.Popen([prism, '-l', inst])
    
    success = False
    fail_reason = None
    mc_proc = None
    
    # Allow up to 120 seconds for slow Forge loading
    for sec in range(120):
        time.sleep(2)
        
        # Check running process
        if not mc_proc:
            for p in psutil.process_iter(['pid', 'name', 'cmdline']):
                try:
                    name = (p.name() or '').lower()
                    if 'java' in name or 'javaw' in name:
                        cmd = ' '.join(p.cmdline() or [])
                        if inst in cmd or 'net.minecraft' in cmd:
                            mc_proc = p
                            break
                except (psutil.NoSuchProcess, psutil.AccessDenied):
                    pass
        
        # Check log file
        if os.path.exists(log_file):
            try:
                with open(log_file, 'r', encoding='utf-8', errors='ignore') as f:
                    content = f.read()
                    if ('Incompatible mods found' in content or 
                        'CrashReport' in content or 
                        'Fatal errors were encountered' in content or
                        'needs language provider' in content):
                        fail_reason = 'Crash / Incompatible mods detected'
                        break
                    
                    # Signals that game has completed loading textures and is on main menu
                    if ('textures/atlas/mob_effects' in content or 
                        'Narrator library' in content or
                        'Sound engine started' in content or
                        'Reloading ResourceManager: vanilla' in content and 'textures/atlas/' in content):
                        # Give it 6 seconds on the actual title screen so user can see it
                        time.sleep(6)
                        success = True
                        break
            except Exception:
                pass
                
        if mc_proc and not mc_proc.is_running():
            fail_reason = 'Process exited before loading complete'
            break

    if success:
        print(f'[SUCCESS] {inst} reached Title Screen!', flush=True)
        results[inst] = 'SUCCESS'
    else:
        reason = fail_reason if fail_reason else 'Timeout (120s)'
        print(f'[FAILED] {inst} failed: {reason}', flush=True)
        results[inst] = f'FAILED ({reason})'
        kill_mc_processes()
        break
        
    print(f'Closing {inst} and moving to next...', flush=True)
    kill_mc_processes()
    time.sleep(3)

print('\n========================================', flush=True)
print('=== ALL 9 INSTANCES TEST SUMMARY ===', flush=True)
for inst, res in results.items():
    print(f'  {inst:15} -> {res}', flush=True)
print('========================================', flush=True)
