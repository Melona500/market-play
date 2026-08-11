import type { CSSProperties } from 'react';
import type { PortraitSprite as Sprite } from '../../services/characterRegistry';

interface Props {
  sprite?: Sprite;
  size?: number;
  className?: string;
}

export function PortraitSprite({ sprite, size = 72, className = '' }: Props) {
  if (!sprite) {
    return (
      <div
        className={`grid place-items-center rounded-xl bg-[#20252d] text-[#5c6472] ${className}`}
        style={{ width: size, height: size }}
      >
        ?
      </div>
    );
  }

  const x = sprite.columns <= 1 ? 0 : (sprite.column / (sprite.columns - 1)) * 100;
  const y = sprite.rows <= 1 ? 0 : (sprite.row / (sprite.rows - 1)) * 100;
  const style: CSSProperties = {
    width: size,
    height: size,
    backgroundImage: `url(${sprite.url})`,
    backgroundSize: `${sprite.columns * 100}% ${sprite.rows * 100}%`,
    backgroundPosition: `${x}% ${y}%`,
    backgroundRepeat: 'no-repeat',
    imageRendering: 'pixelated',
  };

  return <div className={`shrink-0 overflow-hidden rounded-xl bg-[#20252d] ${className}`} style={style} />;
}
