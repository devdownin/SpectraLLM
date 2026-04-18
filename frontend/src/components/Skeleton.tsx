import type { FC } from 'react';

interface SkeletonProps {
  className?: string;
}

const Skeleton: FC<SkeletonProps> = ({ className }) => {
  return (
    <div className={`bg-surface-variant animate-pulse ${className}`} />
  );
};

export default Skeleton;
