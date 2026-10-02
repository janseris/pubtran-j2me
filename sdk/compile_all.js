const cp = require('child_process');
const jadmaker2 = require('./jadmaker2');
const targets = require('../build.json');

const win = (process.platform == 'win32');
const compileScript = win ? "powershell" : "sdk/compile.sh";
const compileScriptArgs = win ? ["sdk\\compile.ps1"] : [];
const classpathJoiner = win ? ";" : ":";

// Every build gets a new MIDlet-Version (1.0.0 -> 1.0.1 ... 1.0.99 -> 1.1.0).
// The Nokia 9300 refuses ("Invalid archive") a jar whose name, vendor and version match
// an already known suite but whose content differs, even after uninstalling. With a new
// version each build installs as an update. The number is kept in manifest.mf, so a commit
// shows which version it built. Set NO_VERSION_BUMP=1 to build without changing it.
if (!process.env.NO_VERSION_BUMP) {
  const fs = require('fs');
  const mf = fs.readFileSync('manifest.mf', 'utf8');
  const m = /^MIDlet-Version: *(\d+)\.(\d+)\.(\d+)/m.exec(mf);
  if (m) {
    let [maj, min, mic] = [+m[1], +m[2], +m[3] + 1];
    if (mic > 99) { mic = 0; min++; }
    if (min > 99) { min = 0; maj++; }
    const v = `${maj}.${min}.${mic}`;
    fs.writeFileSync('manifest.mf', mf.replace(m[0], `MIDlet-Version: ${v}`));
    console.log(`MIDlet-Version: ${v}`);
  }
}

if (win) {
  // Allow compile.ps1 script to run
  cp.execSync("powershell Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy Bypass");
}

const compileTarget = (target) => {
  return new Promise((resolve, reject) => {
    process.env.JAR_NAME = target.name;
    process.env.DEFINES = "-D" + target.defines.join(" -D");
    process.env.BOOTCLASSPATH = target.bootclasspath.join(classpathJoiner);
    process.env.MODCON = Number(target.bootclasspath.some(jar => jar.includes('bouncycastle')));
    process.env.EXCLUDES = (target.excludes || []).join(" ");


    console.log(`${"_".repeat(80)}\n`)
    console.log(` Compiling: ${target.name}`)
    console.log(`${"_".repeat(80)}\n`)

    // shell is only needed to run sdk/compile.sh on Linux/macOS (a script, not an
    // executable) - on Windows we spawn powershell.exe directly with its args, which
    // doesn't need a shell and avoids Node's DEP0190 warning about unescaped shell args.
    const compileProcess = cp.spawn(compileScript, compileScriptArgs, { stdio: 'inherit', shell: !win });

    compileProcess.on('close', (code) => {
      if (code !== 0) {
        console.log("Compilation failed");
        reject(new Error("Compilation failed"));
      } else {
        jadmaker2.createJadFromJar(
          `bin/${target.name}.jar`,
          `bin/${target.name}.jad`,
          `${target.name}.jar`,
          "https://mapy.cz",
          target.sign
        );
        resolve();
      }
    });
  });
};

const compileAll = async () => {
  for (const target of targets) {
    if (target.disabled) {
      console.log(`Skipping disabled target: ${target.name}`);
      continue;
    }
    try {
      await compileTarget(target);
    } catch (error) {
      process.exit(1);
    }
  }
};

compileAll();