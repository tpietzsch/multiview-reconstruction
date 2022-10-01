package net.preibisch.mvrecon.process.downsampling.lazy;

import java.io.File;
import java.util.function.Consumer;

import ij.ImageJ;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.exception.IncompatibleTypeException;
import net.imglib2.img.basictypeaccess.AccessFlags;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.iterator.IntervalIterator;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.process.interestpointdetection.methods.dog.DoGImgLib2;
import net.preibisch.mvrecon.process.interestpointdetection.methods.lazygauss.Lazy;

public class DownsampleRA<T extends RealType<T> & NativeType<T>> implements Consumer<RandomAccessibleInterval<T>>
{
	final T type;
	final private RandomAccessible<T> source;
	final int d,n;
	final long[] globalMin;

	public DownsampleRA(
			final long[] min,
			final RandomAccessible<T> source,
			final Interval sourceInterval,
			final int d, // in which dimension
			final T type )
	{
		this.globalMin = min;
		this.source = source;
		this.type = type;
		this.d = d;
		this.n = source.numDimensions();

		//final Img< T > img = imgFactory.create( dim, Views.iterable( input ).firstElement() );
		//simple2x( src, img, d, taskExecutor );
		//src = img;

	}

	// Note: the output RAI typically sits at 0,0...0 because it usually is a CachedCellImage
	// (but the actual interval to process in many blocks sits somewhere else) 
	@Override
	public void accept( final RandomAccessibleInterval<T> output )
	{
		try
		{
			// no global min, only zero-min is supported for now 

			//long[] min= new long[ output.numDimensions() ];
			//for ( int d = 0; d < min.length; ++d )
			//	min[ d ] = globalMin[ d ] + output.min( d );

			// iterate all dimensions but the one we are processing int
			final long[] iterateMin = new long[ n ];
			final long[] iterateMax = new long[ n ];

			for ( int e = 0; e < n; ++e )
			{
				if ( e == d )
				{
					iterateMin[ e ] = output.min( e );
					iterateMax[ e ] = output.min( e );
				}
				else
				{
					iterateMin[ e ] = output.min( e );
					iterateMax[ e ] = output.max( e );
				}
			}

			final IntervalIterator cursorDim = new IntervalIterator( new FinalInterval(iterateMin, iterateMax));
			final long[] pos = new long[ n ];

			final RandomAccess< T > in = source.randomAccess();
			final RandomAccess< T > out = output.randomAccess();
			final long size = output.max( d ) - output.min( d );

			while (cursorDim.hasNext())
			{
				cursorDim.fwd();
				cursorDim.localize( pos );

				out.setPosition( pos );

				// the first pixel (avoid outofbounds)
				in.setPosition( pos );
				in.setPosition( pos[d]*2, d);
				in.move( globalMin );
				double v0, v1, v2;

				in.bck(d);
				v0 = in.get().getRealDouble();
				in.fwd( d );
				v1 = in.get().getRealDouble();
				in.fwd( d );
				v2 = in.get().getRealDouble();
				out.get().setReal( ( v0 * 0.5 + v1 + v2 * 0.5 ) / 2.0 );

				// other pixels
				for ( long p = 1; p < size; ++p )
				{
					v0 = v2;
					in.fwd( d );
					v1 = in.get().getRealDouble();
					in.fwd( d );
					v2 = in.get().getRealDouble();
					out.fwd( d );
					out.get().setReal( ( v0 * 0.5 + v1 + v2 * 0.5 ) / 2.0 );
				}

				// last pixel
				in.fwd( d );
				v1 = in.get().getRealDouble();
				in.fwd( d );
				v0 = in.get().getRealDouble();
				out.fwd( d );
				out.get().setReal( ( v0 * 0.5 + v1 + v2 * 0.5 ) / 2.0 );
			}
		}
		catch (final IncompatibleTypeException e)
		{
			throw new RuntimeException(e);
		}
	}

	public static void main( String[] args )
	{
		new ImageJ();

		final RandomAccessibleInterval< FloatType > raw =
				IOFunctions.openAs32BitArrayImg( new File( "/Users/preibischs/Documents/Microscopy/SPIM/HisYFP-SPIM/spim_TL18_Angle0.tif"));

		final RandomAccessibleInterval< FloatType > inputCropped = Views.interval( raw, Intervals.expand(raw, new long[] {-200, -200, -20}) );

		final RandomAccessibleInterval< FloatType > input = inputCropped;

		ImageJFunctions.show( inputCropped );

		final long dim[] = new long[ input.numDimensions() ];
		final int d = 1;

		for ( int e = 0; e < input.numDimensions(); ++e )
		{
			if ( e == d )
				dim[ e ] = input.dimension( e ) / 2;
			else
				dim[ e ] = input.dimension( e );
		}

		final long[] min= new long[ input.numDimensions() ];
		input.min( min );

		final DownsampleRA< FloatType > downsampling =
				new DownsampleRA<>(
						min,
						Views.extendMirrorSingle( input ),
						input,
						1,
						new FloatType() );

		final RandomAccessibleInterval<FloatType> downsampled =
				Views.translate( Lazy.process(new FinalInterval( dim ), DoGImgLib2.blockSize, new FloatType(), AccessFlags.setOf(), downsampling ), min );

		ImageJFunctions.show( downsampled );
	}
}