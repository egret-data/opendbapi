import { useId, useState, type FormEvent } from "react";
import { toast } from "sonner";

import { useChangeMyPassword } from "@/api/generated/me/me";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { errorMessage } from "@/lib/errors";

const MIN_PASSWORD = 12;

export function ChangePasswordDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  const change = useChangeMyPassword();
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [confirm, setConfirm] = useState("");
  const [error, setError] = useState<string | null>(null);
  const ids = { current: useId(), next: useId(), confirm: useId() };
  const mismatch = confirm !== "" && confirm !== next;

  const close = () => {
    setCurrent("");
    setNext("");
    setConfirm("");
    setError(null);
    onOpenChange(false);
  };

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (mismatch) return;
    setError(null);
    change.mutate(
      { data: { currentPassword: current, newPassword: next } },
      {
        onSuccess: () => {
          toast.success("Password changed.");
          close();
        },
        onError: (err) => setError(errorMessage(err)),
      },
    );
  };

  return (
    <Dialog open={open} onOpenChange={(o) => (o ? onOpenChange(true) : close())}>
      <DialogContent>
        <form onSubmit={submit} className="space-y-4">
          <DialogHeader>
            <DialogTitle>Change password</DialogTitle>
            <DialogDescription>At least {MIN_PASSWORD} characters.</DialogDescription>
          </DialogHeader>
          <div className="space-y-1">
            <Label htmlFor={ids.current}>Current password</Label>
            <Input id={ids.current} type="password" autoComplete="current-password" required value={current}
              onChange={(e) => setCurrent(e.target.value)} />
          </div>
          <div className="space-y-1">
            <Label htmlFor={ids.next}>New password</Label>
            <Input id={ids.next} type="password" autoComplete="new-password" required minLength={MIN_PASSWORD} value={next}
              onChange={(e) => setNext(e.target.value)} />
          </div>
          <div className="space-y-1">
            <Label htmlFor={ids.confirm}>Repeat new password</Label>
            <Input id={ids.confirm} type="password" autoComplete="new-password" required value={confirm}
              aria-invalid={mismatch} aria-describedby={mismatch ? `${ids.confirm}-err` : undefined}
              onChange={(e) => setConfirm(e.target.value)} />
            {mismatch && (
              <p id={`${ids.confirm}-err`} className="text-xs text-destructive">
                The two new passwords differ.
              </p>
            )}
          </div>
          {error && (
            <p role="alert" className="text-sm text-destructive">
              {error}
            </p>
          )}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={close}>
              Cancel
            </Button>
            <Button type="submit" disabled={change.isPending || mismatch}>
              Change password
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
