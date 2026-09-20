import os
import sys
import glob
import shutil
import subprocess
import time

ROOT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DIST_DIR = os.path.join(ROOT_DIR, 'dist')
os.makedirs(DIST_DIR, exist_ok=True)

ENV = os.environ.copy()
ENV['JAVA_HOME'] = r'C:\OpenJDK\jdk-21'
ENV['PATH'] = r'C:\OpenJDK\jdk-21\bin;' + ENV.get('PATH', '')

TARGETS = [
    # Paper
    ('plugin-paper', r'..\gradlew.bat clean build -x test', 'tailcat-for-minecraft-paper-plugin-0.2.1.jar'),
    # Fabric
    ('mod-fabric-1.20.1', r'..\gradlew.bat clean build -x test', 'tailcat-for-minecraft-fabric-1.20.1-0.2.1.jar'),
    ('mod-fabric-1.21.1', r'..\gradlew.bat clean build -x test', 'tailcat-for-minecraft-fabric-1.21.1-0.2.1.jar'),
    ('mod-fabric-1.21.11', r'..\gradlew.bat clean build -x test', 'tailcat-for-minecraft-fabric-1.21.11-0.2.1.jar'),
    ('mod-fabric-26.2', r'..\gradlew.bat clean build -x test', 'tailcat-for-minecraft-fabric-26.2-0.2.1.jar'),
    # NeoForge
    ('mod-neoforge-1.20.1', r'..\gradlew.bat clean build -x test', 'tailcat-for-minecraft-neoforge-1.20.1-0.2.1.jar'),
    ('mod-neoforge-1.21.1', r'..\gradlew.bat clean build -x test', 'tailcat-for-minecraft-neoforge-1.21.1-0.2.1.jar'),
    ('mod-neoforge-1.21.11', r'..\gradlew.bat clean build -x test', 'tailcat-for-minecraft-neoforge-1.21.11-0.2.1.jar'),
    ('mod-neoforge-26.2', r'..\gradlew.bat clean build -x test', 'tailcat-for-minecraft-neoforge-26.2-0.2.1.jar'),
    # Forge
    ('mod-forge-1.20.1', r'.\gradlew.bat clean build -x test --no-daemon', 'tailcat-for-minecraft-forge-1.20.1-0.2.1.jar'),
    ('mod-forge-1.21.1', r'.\gradlew.bat clean build -x test --no-daemon', 'tailcat-for-minecraft-forge-1.21.1-0.2.1.jar'),
    ('mod-forge-1.21.11', r'.\gradlew.bat clean build -x test --no-daemon', 'tailcat-for-minecraft-forge-1.21.11-0.2.1.jar'),
    ('mod-forge-26.2', r'.\gradlew.bat clean build -x test --no-daemon', 'tailcat-for-minecraft-forge-26.2-0.2.1.jar'),
]

def build_target(module_name, cmd_str, expected_jar_name):
    mod_path = os.path.join(ROOT_DIR, module_name)
    print(f"\n==========================================")
    print(f"Building: {module_name}")
    print(f"Command: {cmd_str}")
    print(f"==========================================")
    start_time = time.time()
    res = subprocess.run(cmd_str, shell=True, cwd=mod_path, env=ENV)
    elapsed = time.time() - start_time
    if res.returncode != 0:
        print(f"FAILED: {module_name} in {elapsed:.1f}s")
        return False

    # Find the output jar
    libs_dir = os.path.join(mod_path, 'build', 'libs')
    target_jar = os.path.join(libs_dir, expected_jar_name)
    if not os.path.exists(target_jar):
        # Search for any jar matching *-0.2.1.jar without -sources
        candidates = [
            f for f in glob.glob(os.path.join(libs_dir, '*.jar'))
            if 'sources' not in f and '0.2.1' in f
        ]
        if candidates:
            target_jar = candidates[0]
        else:
            print(f"FAILED: Could not find output jar in {libs_dir}")
            return False

    dest = os.path.join(DIST_DIR, expected_jar_name)
    shutil.copy2(target_jar, dest)
    size_mb = os.path.getsize(dest) / (1024 * 1024)
    print(f"SUCCESS: {module_name} -> {expected_jar_name} ({size_mb:.2f} MB) in {elapsed:.1f}s")
    return True

def main():
    total_start = time.time()
    results = {}
    for module_name, cmd_str, expected_jar_name in TARGETS:
        ok = build_target(module_name, cmd_str, expected_jar_name)
        results[module_name] = ok
        if not ok:
            print(f"\nSTOPPING early due to failure in {module_name}")
            break

    total_elapsed = time.time() - total_start
    print(f"\n==========================================")
    print(f"BUILD SUMMARY (Total time: {total_elapsed:.1f}s)")
    print(f"==========================================")
    all_ok = True
    for mod, ok in results.items():
        status = "OK" if ok else "FAILED"
        print(f"  {mod:25}: {status}")
        if not ok:
            all_ok = False

    if not all_ok:
        sys.exit(1)

    # Verify dist directory
    dist_files = sorted(os.listdir(DIST_DIR))
    print(f"\nDist directory ({DIST_DIR}) has {len(dist_files)} files:")
    for f in dist_files:
        p = os.path.join(DIST_DIR, f)
        size_mb = os.path.getsize(p) / (1024 * 1024)
        mtime = time.strftime('%Y-%m-%d %H:%M:%S', time.localtime(os.path.getmtime(p)))
        print(f"  {f:50} {size_mb:6.2f} MB  {mtime}")

if __name__ == '__main__':
    main()
