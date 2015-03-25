package spim.fiji.spimdata.imgloaders;

import java.io.File;

import spim.fiji.datasetmanager.LightSheetZ1;
import spim.fiji.datasetmanager.MicroManager;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.sequence.Angle;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.Illumination;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.io.IOFunctions;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.ImgFactory;
import net.imglib2.img.array.ArrayCursor;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;

public class MicroManagerImgLoader extends AbstractImgLoader
{
	final File mmFile;
	final AbstractSequenceDescription< ? extends BasicViewSetup, ? extends BasicViewDescription< ? >, ? > sequenceDescription;

	public MicroManagerImgLoader(
			final File mmFile,
			final ImgFactory< ? extends NativeType< ? > > imgFactory,
			final AbstractSequenceDescription< ? extends BasicViewSetup, ? extends BasicViewDescription< ? >, ? > sequenceDescription )
	{
		super();
		this.mmFile = mmFile;
		this.sequenceDescription = sequenceDescription;

		setImgFactory( imgFactory );
	}

	public File getFile() { return mmFile; }

	final public static < T extends RealType< T > & NativeType< T > > void populateImage( final ArrayImg< T, ? > img, final BasicViewDescription< ? > vd, final MultipageTiffReader r )
	{
		final ArrayCursor< T > cursor = img.cursor();
		
		final int t = vd.getTimePoint().getId();
		final int a = vd.getViewSetup().getAttribute( Angle.class ).getId();
		final int c = vd.getViewSetup().getAttribute( Channel.class ).getId();
		final int i = vd.getViewSetup().getAttribute( Illumination.class ).getId();

		for ( int z = 0; z < r.depth(); ++z )
		{
			final String label = MultipageTiffReader.generateLabel( r.interleavedId( c, a ), z, t, i );
			final Object o = r.readImage( label ).getA();

			if ( o instanceof byte[] )
				for ( final byte b : (byte[])o )
					cursor.next().setReal( UnsignedByteType.getUnsignedByte( b ) );
			else
				for ( final short s : (short[])o )
					cursor.next().setReal( UnsignedShortType.getUnsignedShort( s ) );
		}
	}

	@Override
	public RandomAccessibleInterval< FloatType > getFloatImage( final ViewId view, final boolean normalize )
	{
		try
		{
			final MultipageTiffReader r = new MultipageTiffReader( mmFile );

			final ArrayImg< FloatType, ? > img = ArrayImgs.floats( r.width(), r.height(), r.depth() );
			final BasicViewDescription< ? > vd = sequenceDescription.getViewDescriptions().get( view );

			populateImage( img, vd, r );

			if ( normalize )
				normalize( img );

			updateMetaDataCache( view, r.width(), r.height(), r.depth(), r.calX(), r.calY(), r.calZ() );

			r.close();

			return null;
		}
		catch ( Exception e )
		{
			IOFunctions.println( "Failed to load viewsetup=" + view.getViewSetupId() + " timepoint=" + view.getTimePointId() + ": " + e );
			e.printStackTrace();
			return null;
		}
	}

	@Override
	public RandomAccessibleInterval< UnsignedShortType > getImage( final ViewId view )
	{
		try
		{
			final MultipageTiffReader r = new MultipageTiffReader( mmFile );

			final ArrayImg< UnsignedShortType, ? > img = ArrayImgs.unsignedShorts( r.width(), r.height(), r.depth() );
			final BasicViewDescription< ? > vd = sequenceDescription.getViewDescriptions().get( view );

			populateImage( img, vd, r );

			updateMetaDataCache( view, r.width(), r.height(), r.depth(), r.calX(), r.calY(), r.calZ() );

			r.close();

			return null;
		}
		catch ( Exception e )
		{
			IOFunctions.println( "Failed to load viewsetup=" + view.getViewSetupId() + " timepoint=" + view.getTimePointId() + ": " + e );
			e.printStackTrace();
			return null;
		}
	}

	@Override
	protected void loadMetaData( final ViewId view )
	{
		try
		{
			final MultipageTiffReader r = new MultipageTiffReader( mmFile );

			updateMetaDataCache( view, r.width(), r.height(), r.depth(), r.calX(), r.calY(), r.calZ() );

			r.close();
		}
		catch ( Exception e )
		{
			IOFunctions.println( "Failed to load metadata for viewsetup=" + view.getViewSetupId() + " timepoint=" + view.getTimePointId() + ": " + e );
			e.printStackTrace();
		}
	}

	@Override
	public String toString()
	{
		return new MicroManager().getTitle() + ", ImgFactory=" + imgFactory.getClass().getSimpleName();
	}
}
