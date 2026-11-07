import { useId, type ComponentPropsWithRef } from "react";

// 应用新图标（深色底 + 浅色兔子）里的兔子单色版：身体用 currentColor 填充，眼睛镂空
export default function Logo(props: ComponentPropsWithRef<"svg">) {
  const maskId = `logo-mask-${useId().replace(/[^\w-]/g, "")}`;

  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="-90 -103 712 712"
      width="256"
      height="256"
      fill="currentColor"
      {...props}
    >
      <defs>
        <mask id={maskId} maskUnits="userSpaceOnUse" x="-90" y="-103" width="712" height="712">
          <g transform="translate(320 390) rotate(-24)">
            <g fill="#fff">
              <rect
                x="-150"
                y="-470"
                width="106"
                height="380"
                rx="53"
                transform="rotate(-8 -97 -150)"
              />
              <rect
                x="30"
                y="-480"
                width="106"
                height="380"
                rx="53"
                transform="rotate(10 83 -150)"
              />
              <ellipse cx="0" cy="0" rx="240" ry="215" />
            </g>
            <g fill="#000">
              <rect x="-108" y="-50" width="56" height="116" rx="28" transform="rotate(6 -80 8)" />
              <rect x="22" y="-70" width="56" height="116" rx="28" transform="rotate(6 50 -12)" />
            </g>
          </g>
        </mask>
      </defs>
      <rect x="-90" y="-103" width="712" height="712" mask={`url(#${maskId})`} />
    </svg>
  );
}
