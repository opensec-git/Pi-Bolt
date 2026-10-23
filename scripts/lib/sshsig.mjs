// The release signature in OpenSSH's format (SHA256SUMS.sshsig), made with the same Ed25519 key as SHA256SUMS.sig. The
// installer checks it with `ssh-keygen -Y verify` where there is no OpenSSL 3 (macOS ships LibreSSL, which cannot check
// Ed25519; it ships OpenSSH, which can). The format is OpenSSH's PROTOCOL.sshsig.
// Usage: node scripts/lib/sshsig.mjs sign KEY.pem FILE OUT     writes OUT, the signature of FILE
//        node scripts/lib/sshsig.mjs pubkey PUBLIC.pem         prints the key as "ssh-ed25519 AAAA..."
import { createHash, createPrivateKey, createPublicKey, sign } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";

// The namespace the installer verifies with (-n): a signature made for something else does not pass as a release's.
const NAMESPACE = "pi-bolt-release";

const string = (value) => {
	const bytes = Buffer.from(value);
	const length = Buffer.alloc(4);
	length.writeUInt32BE(bytes.length);
	return Buffer.concat([length, bytes]);
};

const publicBlob = (key) => {
	const raw = Buffer.from(createPublicKey(key).export({ format: "jwk" }).x, "base64url");
	return Buffer.concat([string("ssh-ed25519"), string(raw)]);
};

const [command, ...args] = process.argv.slice(2);
if (command === "pubkey" && args.length === 1) {
	console.log(`ssh-ed25519 ${publicBlob(readFileSync(args[0])).toString("base64")}`);
} else if (command === "sign" && args.length === 3) {
	const [keyFile, file, out] = args;
	const key = createPrivateKey(readFileSync(keyFile));
	if (key.asymmetricKeyType !== "ed25519") throw new Error(`${keyFile} is not an Ed25519 key`);
	const digest = createHash("sha512").update(readFileSync(file)).digest();
	const signed = Buffer.concat([Buffer.from("SSHSIG"), string(NAMESPACE), string(""), string("sha512"), string(digest)]);
	const signature = Buffer.concat([string("ssh-ed25519"), string(sign(null, signed, key))]);
	const version = Buffer.alloc(4);
	version.writeUInt32BE(1);
	const blob = Buffer.concat([
		Buffer.from("SSHSIG"),
		version,
		string(publicBlob(key)),
		string(NAMESPACE),
		string(""),
		string("sha512"),
		string(signature),
	]);
	const armored = blob.toString("base64").match(/.{1,70}/g).join("\n");
	writeFileSync(out, `-----BEGIN SSH SIGNATURE-----\n${armored}\n-----END SSH SIGNATURE-----\n`);
} else {
	console.error("usage: sshsig.mjs sign KEY.pem FILE OUT | sshsig.mjs pubkey PUBLIC.pem");
	process.exit(2);
}
