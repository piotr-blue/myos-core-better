import { useEffect, useState } from "react";
import { UserRound } from "lucide-react";

export function UserAvatar({ imageUrl, size = 26 }: { imageUrl?: string | null; size?: number }) {
  const [failed, setFailed] = useState(false);
  useEffect(() => setFailed(false), [imageUrl]);
  return imageUrl && !failed
    ? <img className="user-avatar" src={imageUrl} width={size} height={size} alt="Your avatar" onError={() => setFailed(true)} />
    : <UserRound size={size} strokeWidth={1.5} aria-hidden="true" />;
}
