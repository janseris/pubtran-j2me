const cp = require('child_process');
const jadmaker2 = require('./jadmaker2');
const targets = require('../build.json');

const win = (process.platform == 'win32');
const compileScript = win ? "powershell" : "sdk/compile.sh";
const compileScriptArgs = win ? ["sdk\\compile.ps1"] : [];
const classpathJoiner = win ? ";" : ":";

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