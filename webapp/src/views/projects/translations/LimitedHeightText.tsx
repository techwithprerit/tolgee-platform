import { useState, useRef, useEffect, RefObject } from 'react';
import { makeStyles } from '@material-ui/core';

const useStyles = makeStyles({
  '@keyframes fadeIn': {
    from: { opacity: 0 },
    to: { opacity: 1 },
  },
  container: {
    position: 'relative',
    display: 'flex',
    overflow: 'hidden',
    lineHeight: '1.2rem',

    // Adds a hyphen where the word breaks
    '-ms-hyphens': 'auto',
    '-moz-hyphens': 'auto',
    '-webkit-hyphens': 'auto',
    hyphens: 'auto',
    animationName: '$fadeIn',
    animationDuration: '0.1s',
    animationTimingFunction: 'ease-in-out',
  },
});

type Props = {
  maxLines?: number | undefined;
  lang?: string;
  wrap?: 'break-word' | 'break-all';
  width: number;
};

export const LimitedHeightText: React.FC<Props> = ({
  maxLines,
  children,
  lang,
  wrap = 'break-word',
  width,
}) => {
  const classes = useStyles();
  const textRef = useRef<HTMLDivElement>();
  const [expandable, setExpandable] = useState<boolean>(false);

  const detectExpandability = () => {
    const textElement = textRef.current;
    if (textElement != null) {
      const clone = textElement.cloneNode(true) as HTMLDivElement;
      clone.style.position = 'absolute';
      clone.style.visibility = 'hidden';
      clone.style.top = '0px';
      textElement.parentElement?.append(clone);
      setExpandable(textElement.clientHeight < clone.scrollHeight);
      textElement.parentElement?.removeChild(clone);
    }
  };

  useEffect(() => {
    detectExpandability();
  }, [width, children, wrap, maxLines]);

  const gradient = expandable
    ? `linear-gradient(to top, rgba(0,0,0,0) 0%, rgba(0,0,0,0.87) 1.2rem, rgba(0,0,0,0.87) ${
        100 / (maxLines || 100)
      }%, black 100%)`
    : undefined;

  return (
    <div
      className={classes.container}
      ref={textRef as RefObject<HTMLDivElement>}
      style={{
        maxHeight: maxLines ? `calc(1.2rem * ${maxLines})` : undefined,
        WebkitMaskImage: gradient,
        maskImage: gradient,
        wordBreak: wrap,
      }}
      lang={lang}
    >
      {children}
    </div>
  );
};
