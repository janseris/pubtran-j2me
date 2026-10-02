const fs = require('fs');
const crypto = require("crypto");
const AdmZip = require("adm-zip");

// sign: true = the "Darkman" key/certificate from discord-j2me (sdk/exp.pem, exp.cer);
// sign: "own" = this project's own key/certificate (sdk/sign/own.pem, own_cert.pem,
// pubtran-sign.cer to install on the phone).
function signingKeyAndCert(sign) {
    if (sign === "own" || sign === "chain") {
        // "own": one self-signed certificate (pubtran-sign.cer on the phone);
        // "chain": signer certificate issued by a separate root (pubtran-root.cer on the phone)
        const base = sign === "own" ? "own" : "leaf";
        const pem = fs.readFileSync("./sdk/sign/" + base + "_cert.pem", "utf8");
        const b64 = pem.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
        return { key: fs.readFileSync("./sdk/sign/" + base + ".pem", "utf8"), cert: b64 };
    }
    return { key: fs.readFileSync("./sdk/exp.pem", "utf8"), cert: null };
}

function createJadFromJar(path, outPath, jarUrl, infoUrl, sign) {
    const zip = new AdmZip(path);

    const mfEntry = zip.getEntries().find(e => e.entryName.toLowerCase() == "meta-inf/manifest.mf");
    if (!mfEntry) throw new Error("The JAR file does not have a manifest.");

    const mf = parseJad(mfEntry.getData().toString());
    const jar = fs.readFileSync(path);

    mf.set("MIDlet-Jar-Size", jar.length);
    mf.set("MIDlet-Jar-URL", jarUrl);
    mf.set("MIDlet-Info-URL", infoUrl);

    if (sign) {
        const signer = crypto.createSign("RSA-SHA1");
        signer.update(jar);
        signer.end();

        const signing = signingKeyAndCert(sign);
        const signature = signer
            .sign(signing.key)
            .toString("base64")
            .match(/.{1,64}/g)
            .join("");

        mf.set("MIDlet-Jar-RSA-SHA1", signature);

        // hardcoded base64 of darkman cert
        if (signing.cert) mf.set("MIDlet-Certificate-1-1", signing.cert);
        else mf.set("MIDlet-Certificate-1-1","MIIB7jCCAVcCBEWxvN0wDQYJKoZIhvcNAQEEBQAwPTELMAkGA1UEBhMCUlUxDTALBgNVBAoTBG5vbmUxDTALBgNVBAsTBG5vbmUxEDAOBgNVBAMTB0RhcmttYW4wIBcNMDcwMTIwMDY1NTI1WhgPMjA3NTA3MDIwNjU1MjVaMD0xCzAJBgNVBAYTAlJVMQ0wCwYDVQQKEwRub25lMQ0wCwYDVQQLEwRub25lMRAwDgYDVQQDEwdEYXJrbWFuMIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCYLFUb8RYT89sbTAEE14ApYFI8PVpnxXGgLuE8V6+XGQu4q5MtYwmip8EMm/STLXb73gQmDnQUpwBKzTScXLQDA4n9lLni4yl29/+X5Y0rIA6tlPmK3p9wpt0t9j/rWEYF4zFsiMTNobGHZOK/MAxOM+wPICRW8DFLQ/rYcNjcpQIDAQABMA0GCSqGSIb3DQEBBAUAA4GBAG1fDNKSjvcvvi20AREsMT80iJdO/YXqcVrUsYrQVaZL3scsA+EVKi7Dv76c8oqjxxiueOnn+fTTmlkAOO5ngzZhk13m3tcNxwUs0A/1GBMbVDbYlEc6vEYQde9x+07iyrxmtwj6qVR1r3zTEy2wS52poVmCkcrPXY0wylKesjFP");
    }

    fs.writeFileSync(outPath, createJad(mf));
}

function parseJad(jad) {
    const result = new Map();

    jad = jad.replace(/\r\n|\r/g, "\n");  // convert line endings

    jad.replace(/^\s*?([\w-]+)\s*\:\s+((.|\n )+?)$(?=\n[^ ])/gm, (_, key, value) => {
        if (result.has(key)) {
            throw new Error(`The JAD file has a duplicate attribute: "${key}"`);
        }
        result.set(key, value.replace(/\n /g, ""));
    })
    return result;
}

function createJad(map) {
    let result = "";

    // One attribute per line, CRLF. The MIDP JAD format has no continuation lines (that's
    // a JAR-manifest feature): the Nokia 9300 installer drops wrapped attributes and then
    // reports "no signature present" for a signed JAD.
    map.forEach((value, key) => {
        if (key === "Manifest-Version" || key === "Created-By") return;
        result += key + ": " + value.toString() + "\r\n";
    })

    return result;
}

module.exports = {
    createJadFromJar,
    parseJad,
    createJad
}