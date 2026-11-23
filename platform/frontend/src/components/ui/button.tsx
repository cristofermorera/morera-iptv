import { Slot } from '@radix-ui/react-slot'
import { cva, type VariantProps } from 'class-variance-authority'
import { clsx } from 'clsx'
import { twMerge } from 'tailwind-merge'
import type { ComponentProps } from 'react'

const variants = cva('inline-flex items-center justify-center gap-2 font-semibold disabled:opacity-60', {
  variants: { variant: { default: 'primary', outline: 'category', ghost: 'icon-button' } },
  defaultVariants: { variant: 'default' },
})

export function Button({ className, variant, asChild, ...props }: ComponentProps<'button'> & VariantProps<typeof variants> & { asChild?: boolean }) {
  const Component = asChild ? Slot : 'button'
  return <Component data-slot="button" className={twMerge(clsx(variants({ variant }), className))} {...props} />
}
