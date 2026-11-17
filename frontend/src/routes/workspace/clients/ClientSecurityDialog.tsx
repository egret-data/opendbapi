import { useState, type FormEvent } from "react";
import { Copy } from "lucide-react";
import { toast } from "sonner";

import { useSaveClientSecurity } from "@/api/generated/service-accounts/service-accounts";
import type { Client } from "@/api/generated/model";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { Textarea } from "@/components/ui/textarea";
import { errorMessage } from "@/lib/errors";

/** Signed requests (spec §8.2) and encrypted responses (spec §8.3) for one client. */
export function ClientSecurityDialog({ ws, saId, client, onClose, onSaved }: {
  ws: string; saId: string; client: Client; onClose: () => void; onSaved: () => void;
}) {
  const [hmac, setHmac] = useState(client.hmacRequired);
  const [rotate, setRotate] = useState(false);
  const [encrypt, setEncrypt] = useState(client.responseEncrypt);
  const [publicKey, setPublicKey] = useState(client.publicKey ?? "");
  const [error, setError] = useState<string | null>(null);
  const [issuedKey, setIssuedKey] = useState<string | null>(null);
  const save = useSaveClientSecurity();

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    save.mutate({
      ws, id: saId, clientRowId: client.id,
      data: { hmacRequired: hmac, rotateSigningKey: hmac && rotate, responseEncrypt: encrypt, publicKey: publicKey.trim() || null },
    }, {
      onSuccess: (r) => {
        onSaved();
        if (r.signingKey) setIssuedKey(r.signingKey);
        else onClose();
      },
      onError: (err) => setError(errorMessage(err)),
    });
  };

  if (issuedKey) {
    return (
      // Like a new secret: not closable by Escape or an outside click, the key is not shown again.
      <Dialog open>
        <DialogContent showCloseButton={false} onEscapeKeyDown={(e) => e.preventDefault()} onInteractOutside={(e) => e.preventDefault()}>
          <DialogHeader>
            <DialogTitle>Signing key issued</DialogTitle>
            <DialogDescription>Copy it now; it is stored encrypted and cannot be shown again.</DialogDescription>
          </DialogHeader>
          <div className="flex items-center gap-2">
            <code className="min-w-0 flex-1 break-all rounded-md border bg-muted px-2 py-1.5 text-xs">{issuedKey}</code>
            <Button variant="outline" size="icon" aria-label="Copy signing key"
              onClick={() => navigator.clipboard.writeText(issuedKey).then(() => toast.success("Signing key copied."))}>
              <Copy className="h-4 w-4" />
            </Button>
          </div>
          <p className="text-sm text-muted-foreground">
            Sign each request: X-Signature = base64(HMAC-SHA256(key, METHOD, path, sorted query, SHA-256 of the body in hex,
            X-Timestamp in ms, X-Nonce; joined by newlines)).
          </p>
          <DialogFooter><Button onClick={onClose}>I have stored the key</Button></DialogFooter>
        </DialogContent>
      </Dialog>
    );
  }

  return (
    <Dialog open onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent className="sm:max-w-xl">
        <form onSubmit={submit} className="space-y-4">
          <DialogHeader>
            <DialogTitle>Client security</DialogTitle>
            <DialogDescription className="font-mono text-xs">{client.clientId}</DialogDescription>
          </DialogHeader>
          <div className="space-y-2">
            <div className="flex items-center gap-2">
              <Switch id="sec-hmac" checked={hmac} onCheckedChange={setHmac} />
              <Label htmlFor="sec-hmac">Require signed requests</Label>
            </div>
            <p className="text-xs text-muted-foreground">Requests without a valid HMAC signature, older than 5 minutes or replayed are refused.</p>
            {hmac && client.hmacRequired && (
              <div className="flex items-center gap-2">
                <Checkbox id="sec-rotate" checked={rotate} onCheckedChange={(c) => setRotate(c === true)} />
                <Label htmlFor="sec-rotate" className="font-normal">Issue a new signing key (the current one stops working)</Label>
              </div>
            )}
          </div>
          <div className="space-y-2">
            <div className="flex items-center gap-2">
              <Switch id="sec-encrypt" checked={encrypt} onCheckedChange={setEncrypt} />
              <Label htmlFor="sec-encrypt">Encrypt responses</Label>
            </div>
            <p className="text-xs text-muted-foreground">
              Answers become {"{encryptedKey, iv, data}"}: AES-256-GCM, the key wrapped with this RSA key (OAEP, SHA-256). Streams are refused.
            </p>
            <Label htmlFor="sec-key" className="sr-only">Public key</Label>
            <Textarea id="sec-key" rows={5} className="font-mono text-xs" value={publicKey} disabled={!encrypt && !publicKey}
              placeholder="-----BEGIN PUBLIC KEY-----" onChange={(e) => setPublicKey(e.target.value)} />
          </div>
          {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>Cancel</Button>
            <Button type="submit" disabled={save.isPending}>Save</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
