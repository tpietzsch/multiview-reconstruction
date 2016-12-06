package spim.fiji.datasetmanager;

import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.Label;
import java.awt.Panel;
import java.awt.TextField;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.InputMethodEvent;
import java.awt.event.InputMethodListener;
import java.awt.event.TextEvent;
import java.awt.event.TextListener;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import javax.swing.JLabel;

import bdv.export.ExportMipmapInfo;
import bdv.export.ProgressWriter;
import fiji.util.gui.GenericDialogPlus;
import ij.ImageJ;
import ij.gui.GenericDialog;
import loci.formats.IFormatReader;
import loci.formats.in.ZeissCZIReader;
import mdbtools.dbengine.sql.Join;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.registration.ViewRegistrations;
import mpicbg.spim.data.registration.ViewTransform;
import mpicbg.spim.data.registration.ViewTransformAffine;
import mpicbg.spim.data.sequence.Angle;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.Illumination;
import mpicbg.spim.data.sequence.ImgLoader;
import mpicbg.spim.data.sequence.MissingViews;
import mpicbg.spim.data.sequence.SequenceDescription;
import mpicbg.spim.data.sequence.Tile;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.TimePoints;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.ViewSetup;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.Dimensions;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.cell.CellImgFactory;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import spim.fiji.datasetmanager.FileListDatasetDefinitionUtil.AngleInfo;
import spim.fiji.datasetmanager.FileListDatasetDefinitionUtil.ChannelInfo;
import spim.fiji.datasetmanager.FileListDatasetDefinitionUtil.CheckResult;
import spim.fiji.datasetmanager.FileListDatasetDefinitionUtil.TileInfo;
import spim.fiji.datasetmanager.patterndetector.FilenamePatternDetector;
import spim.fiji.datasetmanager.patterndetector.NumericalFilenamePatternDetector;
import spim.fiji.plugin.resave.Generic_Resave_HDF5;
import spim.fiji.plugin.resave.ProgressWriterIJ;
import spim.fiji.plugin.resave.Resave_HDF5;
import spim.fiji.plugin.resave.Generic_Resave_HDF5.Parameters;
import spim.fiji.plugin.util.GUIHelper;
import spim.fiji.spimdata.SpimData2;
import spim.fiji.spimdata.boundingbox.BoundingBoxes;
import spim.fiji.spimdata.imgloaders.FileMapImgLoaderLOCI;
import spim.fiji.spimdata.imgloaders.LegacyFileMapImgLoaderLOCI;
import spim.fiji.spimdata.interestpoints.ViewInterestPoints;
import spim.fiji.spimdata.stitchingresults.StitchingResults;

public class FileListDatasetDefinition implements MultiViewDatasetDefinition
{
	private static final String[] GLOB_SPECIAL_CHARS = new String[] {"{", "}", "[", "]", "*", "?"};
	
	private static ArrayList<FileListChooser> fileListChoosers = new ArrayList<>();
	static
	{
		fileListChoosers.add( new WildcardFileListChooser() );
	}
	
	private static interface FileListChooser
	{
		public List<File> getFileList();
		public String getDescription();
		public FileListChooser getNewInstance();
	}
	
	private static class WildcardFileListChooser implements FileListChooser
	{

		private static long KB_FACTOR = 1024;
		private static int minNumLines = 10;
		private static String info = "<html> <h1> Select files via wildcard expression </h1> <br /> "
				+ "Use the path field to specify a file or directory to process or click 'Browse...' to select one. <br /> <br />"
				+ "Wildcard (*) expressions are allowed. <br />"
				+ "e.g. '/Users/spim/data/spim_TL*_Angle*.tif' <br /><br />"
				+ "</html>";
		
		
		private static String previewFiles(List<File> files){
			StringBuilder sb = new StringBuilder();
			sb.append("<html><h2> selected files </h2>");
			for (File f : files)
				sb.append( "<br />" + f.getAbsolutePath() );
			for (int i = 0; i < minNumLines - files.size(); i++)
				sb.append( "<br />"  );
			sb.append( "</html>" );
			return sb.toString();
		}
		
		@Override
		public List< File > getFileList()
		{
						
			GenericDialogPlus gdp = new GenericDialogPlus("Pick files to include");
			
			gdp.addMessage( info );
			gdp.addDirectoryOrFileField( "path", "/", 65);
			gdp.addNumericField( "exclude files smaller than (KB)", 10, 0 );
			
			// add empty preview
			gdp.addMessage(previewFiles( new ArrayList<>()), GUIHelper.smallStatusFont);
			
			Label lab = (Label)gdp.getComponent( 5 );
			TextField num = (TextField)gdp.getComponent( 4 ); 
			Panel pan = (Panel)gdp.getComponent( 2 );
			
			num.addTextListener( new TextListener()
			{
				
				@Override
				public void textValueChanged(TextEvent e)
				{
					String path = ((TextField)pan.getComponent( 0 )).getText();
					if (path.endsWith( File.separator ))
						path = path.substring( 0, path.length() - File.separator.length() );
					
					if(new File(path).isDirectory())
						path = String.join( File.separator, path, "*" );
					
					lab.setText( previewFiles( getFilesFromPattern(path , Long.parseLong( num.getText() ) * KB_FACTOR)));
					lab.setSize( lab.getPreferredSize() );
					gdp.setSize( gdp.getPreferredSize() );
					gdp.validate();
				}
			} );
			
			((TextField)pan.getComponent( 0 )).addTextListener( new TextListener()
			{
				
				@Override
				public void textValueChanged(TextEvent e)
				{
					String path = ((TextField)pan.getComponent( 0 )).getText();
					if (path.endsWith( File.separator ))
						path = path.substring( 0, path.length() - File.separator.length() );
					
					if(new File(path).isDirectory())
						path = String.join( File.separator, path, "*" );
					
					lab.setText( previewFiles( getFilesFromPattern(path , Long.parseLong( num.getText() ) * KB_FACTOR)));
					lab.setSize( lab.getPreferredSize() );
					gdp.setSize( gdp.getPreferredSize() );
					gdp.validate();
				}
			} );
			
			GUIHelper.addScrollBars( gdp );			
			gdp.showDialog();
			
			
			
			
			if (gdp.wasCanceled())
				return new ArrayList<>();
			
			String fileInput = gdp.getNextString();
			
			if (fileInput.endsWith( File.separator ))
				fileInput = fileInput.substring( 0, fileInput.length() - File.separator.length() );
			
			if(new File(fileInput).isDirectory())
				fileInput = String.join( File.separator, fileInput, "*" );
			
			List<File> files = getFilesFromPattern( fileInput, (long) gdp.getNextNumber() * KB_FACTOR );
			
			files.forEach(f -> System.out.println( "Including file " + f + " in dataset." ));
			
			return files;
		}

		@Override
		public String getDescription(){return "Choose via wildcard expression";}

		@Override
		public FileListChooser getNewInstance() {return new WildcardFileListChooser();}
		
	}
	
		
	
	public static List<File> getFilesFromPattern(String pattern, final long fileMinSize)
	{		
		Pair< String, String > pAndp = splitIntoPathAndPattern( pattern, GLOB_SPECIAL_CHARS );		
		String path = pAndp.getA();
		String justPattern = pAndp.getB();
		
		PathMatcher pm = FileSystems.getDefault().getPathMatcher( "glob:" + 
				((justPattern.length() == 0) ? path : String.join( File.separator, path, justPattern )) );
		
		List<File> paths = new ArrayList<>();
		
		if (!new File( path ).exists())
			return paths;
		
		int numLevels = justPattern.split( File.separator ).length;
						
		try
		{
			Files.walk( Paths.get( path ), numLevels ).filter( p -> pm.matches( p ) ).filter( new Predicate< Path >()
			{

				@Override
				public boolean test(Path t)
				{
					try
					{
						return Files.size( t ) > fileMinSize;
					}
					catch ( IOException e )
					{
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					return false;
				}
			} )
			.forEach( p -> paths.add( new File(p.toString() )) );

		}
		catch ( IOException e )
		{
			
		}
		
		return paths;
	}
	
	private static SpimData2 buildSpimData(
			Map< Integer, List< Pair< File, Pair< Integer, Integer > > > > tpIdxMap,
			Map< Integer, List< Pair< File, Pair< Integer, Integer > > > > channelIdxMap,
			Map< Integer, List< Pair< File, Pair< Integer, Integer > > > > illumIdxMap,
			Map< Integer, List< Pair< File, Pair< Integer, Integer > > > > tileIdxMap,
			Map< Integer, List< Pair< File, Pair< Integer, Integer > > > > angleIdxMap,
			Map< Integer, ChannelInfo > channelDetailMap,
			Map< Integer, TileInfo > tileDetailMap,
			Map< Integer, AngleInfo > angleDetailMap,
			Map<Pair<File, Pair< Integer, Integer >>, Pair<Dimensions, VoxelDimensions>> dimensionMap)
	{
		
		//final Map< Integer, List< Pair< File, Pair< Integer, Integer > > > > fm = tileIdxMap;
		//fm.forEach( (k,v ) -> {System.out.println( k ); v.forEach( p -> {System.out.print(p.getA() + ""); System.out.print(p.getB().getA().toString() + " "); System.out.println(p.getB().getB().toString());} );});
		
		
		
		List<Integer> timepointIndexList = new ArrayList<>(tpIdxMap.keySet());
		List<Integer> channelIndexList = new ArrayList<>(channelIdxMap.keySet());
		List<Integer> illuminationIndexList = new ArrayList<>(illumIdxMap.keySet());
		List<Integer> tileIndexList = new ArrayList<>(tileIdxMap.keySet());
		List<Integer> angleIndexList = new ArrayList<>(angleIdxMap.keySet());
		
		Collections.sort( timepointIndexList );
		Collections.sort( channelIndexList );
		Collections.sort( illuminationIndexList );
		Collections.sort( tileIndexList );
		Collections.sort( angleIndexList );
		
		int nTimepoints = timepointIndexList.size();
		int nChannels = channelIndexList.size();
		int nIlluminations = illuminationIndexList.size();
		int nTiles = tileIndexList.size();
		int nAngles = angleIndexList.size();
		
		List<ViewSetup> viewSetups = new ArrayList<>();
		List<ViewId> missingViewIds = new ArrayList<>();
		List<TimePoint> timePoints = new ArrayList<>();
		
		
		
		
		HashMap<Pair<Integer, Integer>, Pair<File, Pair<Integer, Integer>>> ViewIDfileMap = new HashMap<>();
		Integer viewSetupId = 0;
		for (Integer c = 0; c < nChannels; c++)
			for (Integer i = 0; i < nIlluminations; i++)
				for (Integer ti = 0; ti < nTiles; ti++)
					for (Integer a = 0; a < nAngles; a++)
					{
						// remember if we already added a vs in the tp loop
						boolean addedViewSetup = false;
						for (Integer tp = 0; tp < nTimepoints; tp++)
						{
														
							List< Pair< File, Pair< Integer, Integer > > > viewList;
							viewList = FileListDatasetDefinitionUtil.listIntersect( channelIdxMap.get( channelIndexList.get( c ) ), angleIdxMap.get( angleIndexList.get( a ) ) );
							viewList = FileListDatasetDefinitionUtil.listIntersect( viewList, tileIdxMap.get( tileIndexList.get( ti ) ) );
							viewList = FileListDatasetDefinitionUtil.listIntersect( viewList, illumIdxMap.get( illuminationIndexList.get( i ) ) );
							
							// we only consider combinations of angle, illum, channel, tile that are in at least one timepoint
							if (viewList.size() == 0)
								continue;
							
							viewList = FileListDatasetDefinitionUtil.listIntersect( viewList, tpIdxMap.get( timepointIndexList.get( tp ) ) );

														
							Integer tpId = timepointIndexList.get( tp );
							Integer channelId = channelIndexList.get( c );
							Integer illuminationId = illuminationIndexList.get( i );
							Integer angleId = angleIndexList.get( a );
							Integer tileId = tileIndexList.get( ti );
							
							System.out.println( "VS: " + viewSetupId );
							
							if (viewList.size() < 1)
							{
								System.out.println( "Missing View: ch" + c +" a"+ a + " ti" + ti + " tp"+ tp + " i" + i );
								int missingSetup = addedViewSetup ? viewSetupId - 1 : viewSetupId;
								missingViewIds.add( new ViewId( tpId, missingSetup ) );
								
							}
							else if (viewList.size() > 1)
								System.out.println( "Error: more than one View: ch" + c +" a"+ a + " ti" + ti + " tp"+ tp + " i" + i );
							else
							{
								System.out.println( "Found View: ch" + c +" a"+ a + " ti" + ti + " tp"+ tp + " i" + i + " in file " + viewList.get( 0 ).getA().getAbsolutePath());
								
								TimePoint tpI = new TimePoint( tpId );
								if (!timePoints.contains( tpI ))
									timePoints.add( tpI );
								
								if (!addedViewSetup)
									ViewIDfileMap.put( new ValuePair< Integer, Integer >( tpId, viewSetupId ), viewList.get( 0 ) );
								else
									ViewIDfileMap.put( new ValuePair< Integer, Integer >( tpId, viewSetupId - 1 ), viewList.get( 0 ) );
								
								
								// we have not visited this combination before
								if (!addedViewSetup)
								{
									Illumination illumI = new Illumination( illuminationId, illuminationId.toString() );
									
									Channel chI = new Channel( channelId, channelId.toString() );
									
									if (channelDetailMap != null && channelDetailMap.containsKey( channelId))
									{
										ChannelInfo chInfoI = channelDetailMap.get( channelId );
										if (chInfoI.wavelength != null)
											chI.setName( Integer.toString( (int)Math.round( chInfoI.wavelength )));
										if (chInfoI.fluorophore != null)
											chI.setName( chInfoI.fluorophore );
										if (chInfoI.name != null)
											chI.setName( chInfoI.name );
									}
									
									
									Angle aI = new Angle( angleId, angleId.toString() );
									
									if (angleDetailMap != null && angleDetailMap.containsKey( angleId ))
									{
										AngleInfo aInfoI = angleDetailMap.get( angleId );
										
										if (aInfoI.angle != null && aInfoI.axis != null)
										{
											// TODO: set rotation here
										}
									}
									
									Tile tI = new Tile( tileId, tileId.toString() );
									
									if (tileDetailMap != null && tileDetailMap.containsKey( tileId ))
									{
										TileInfo tInfoI = tileDetailMap.get( tileId );
										if (tInfoI.locationX != null) // TODO: clean check here
											tI.setLocation( new double[] {tInfoI.locationX, tInfoI.locationY, tInfoI.locationZ} );
									}
																		
									ViewSetup vs = new ViewSetup( viewSetupId, 
													viewSetupId.toString(), 
													dimensionMap.get( (viewList.get( 0 ))).getA(),
													dimensionMap.get( (viewList.get( 0 ))).getB(),
													tI, chI, aI, illumI );
									
									viewSetups.add( vs );
									viewSetupId++;
									addedViewSetup = true;
								
								}
								
							}
						}
					}
		
		
		
		SequenceDescription sd = new SequenceDescription( new TimePoints( timePoints ), viewSetups , null, new MissingViews( missingViewIds ));
		
		HashMap<BasicViewDescription< ? >, Pair<File, Pair<Integer, Integer>>> fileMap = new HashMap<>();
		for (Pair<Integer, Integer> k : ViewIDfileMap.keySet())
		{
			System.out.println( k.getA() + " " + k.getB() );
			ViewDescription vdI = sd.getViewDescription( k.getA(), k.getB() );
			System.out.println( vdI );
			if (vdI != null && vdI.isPresent()){
				fileMap.put( vdI, ViewIDfileMap.get( k ) );
			}			
		}
		
		ImgLoader imgLoader = new FileMapImgLoaderLOCI( fileMap, FileListDatasetDefinitionUtil.selectImgFactory(dimensionMap), sd );
		sd.setImgLoader( imgLoader );
		
		double minResolution = Double.MAX_VALUE;
		for ( VoxelDimensions d : dimensionMap.values().stream().map( p -> p.getB() ).collect( Collectors.toList() ) )
		{
			for (int di = 0; di < d.numDimensions(); di++)
				minResolution = Math.min( minResolution, d.dimension( di ) );
		}
		
		
		ViewRegistrations vrs = createViewRegistrations( sd.getViewDescriptions(), minResolution );
		
		// create the initial view interest point object
		final ViewInterestPoints viewInterestPoints = new ViewInterestPoints();
		viewInterestPoints.createViewInterestPoints( sd.getViewDescriptions() );
		
		SpimData2 data = new SpimData2( new File("/Users/david/Desktop"), sd, vrs, viewInterestPoints, new BoundingBoxes(), new StitchingResults() );
		return data;
	}
	
	/**
	 * Assembles the {@link ViewRegistration} object consisting of a list of {@link ViewRegistration}s for all {@link ViewDescription}s that are present
	 * 
	 * @param viewDescriptionList
	 * @param minResolution - the smallest resolution in any dimension (distance between two pixels in the output image will be that wide)
	 * @return
	 */
	protected static ViewRegistrations createViewRegistrations( final Map< ViewId, ViewDescription > viewDescriptionList, final double minResolution )
	{
		final HashMap< ViewId, ViewRegistration > viewRegistrationList = new HashMap< ViewId, ViewRegistration >();
		
		for ( final ViewDescription viewDescription : viewDescriptionList.values() )
			if ( viewDescription.isPresent() )
			{
				final ViewRegistration viewRegistration = new ViewRegistration( viewDescription.getTimePointId(), viewDescription.getViewSetupId() );
				
				final VoxelDimensions voxelSize = viewDescription.getViewSetup().getVoxelSize(); 

				final double calX = voxelSize.dimension( 0 ) / minResolution;
				final double calY = voxelSize.dimension( 1 ) / minResolution;
				final double calZ = voxelSize.dimension( 2 ) / minResolution;
				
				final AffineTransform3D m = new AffineTransform3D();
				m.set( calX, 0.0f, 0.0f, 0.0f, 
					   0.0f, calY, 0.0f, 0.0f,
					   0.0f, 0.0f, calZ, 0.0f );
				final ViewTransform vt = new ViewTransformAffine( "calibration", m );
				viewRegistration.preconcatenateTransform( vt );
				
				final Tile tile = viewDescription.getViewSetup().getAttribute( Tile.class );

				if (tile.hasLocation()){
					final double shiftX = tile.getLocation()[0] / voxelSize.dimension( 0 );
					final double shiftY = tile.getLocation()[1] / voxelSize.dimension( 1 );
					final double shiftZ = tile.getLocation()[2] / voxelSize.dimension( 2 );
					
					final AffineTransform3D m2 = new AffineTransform3D();
					m2.set( 1.0f, 0.0f, 0.0f, shiftX, 
						   0.0f, 1.0f, 0.0f, shiftY,
						   0.0f, 0.0f, 1.0f, shiftZ );
					final ViewTransform vt2 = new ViewTransformAffine( "Translation", m2 );
					viewRegistration.concatenateTransform( vt2 );
				}
				
				viewRegistrationList.put( viewRegistration, viewRegistration );
			}
		
		return new ViewRegistrations( viewRegistrationList );
	}
	
	
	

	@Override
	public SpimData2 createDataset( )
	{
		
		String[] fileListChooserChoices = new String[fileListChoosers.size()];
		for (int i = 0; i< fileListChoosers.size(); i++)
			fileListChooserChoices[i] = fileListChoosers.get( i ).getDescription();		
		
		GenericDialog gd1 = new GenericDialog( "How to select files" );
		gd1.addChoice( "file chooser", fileListChooserChoices, fileListChooserChoices[0] );
		gd1.showDialog();
		
		if (gd1.wasCanceled())
			return null;
		
		List<File> files = fileListChoosers.get( gd1.getNextChoiceIndex() ).getFileList();			
		
		List<FileListDatasetDefinitionUtil.CheckResult> multiplicityMap = Arrays.asList( new FileListDatasetDefinitionUtil.CheckResult[] {FileListDatasetDefinitionUtil.CheckResult.SINGLE, FileListDatasetDefinitionUtil.CheckResult.SINGLE, FileListDatasetDefinitionUtil.CheckResult.SINGLE, FileListDatasetDefinitionUtil.CheckResult.SINGLE, FileListDatasetDefinitionUtil.CheckResult.SINGLE} );
		Map<Integer, List<Pair<File, Pair< Integer, Integer >>>> accumulateTPMap = new HashMap<>();
		Map<FileListDatasetDefinitionUtil.ChannelInfo, List<Pair<File, Pair<Integer, Integer>>>> accumulateChannelMap = new HashMap<>();
		Map<Integer, List<Pair<File, Pair<Integer, Integer>>>> accumulateIllumMap = new HashMap<>();
		Map<FileListDatasetDefinitionUtil.TileInfo, List<Pair<File, Pair< Integer, Integer >>>> accumulateTileMap = new HashMap<>();
		Map<FileListDatasetDefinitionUtil.AngleInfo, List<Pair<File, Pair< Integer, Integer >>>> accumulateAngleMap = new HashMap<>();
		Boolean ambiguousAngleTile = false;
		Boolean ambiguousIllumChannel = false;
		Map<Pair<File, Pair< Integer, Integer >>, Pair<Dimensions, VoxelDimensions>> dimensionMap = new HashMap<>();
		
		FileListDatasetDefinitionUtil.detectViewsInFiles( files,
							multiplicityMap,
							accumulateTPMap,
							accumulateChannelMap,
							accumulateIllumMap,
							accumulateTileMap,
							accumulateAngleMap,
							ambiguousAngleTile,
							ambiguousIllumChannel,
							dimensionMap);
		
		
		
		
		
		List<Integer> fileVariableToUse = Arrays.asList( new Integer[] {null, null, null, null, null} );
		List<String> choices = new ArrayList<>();
		
		FilenamePatternDetector patternDetector = new NumericalFilenamePatternDetector();
		patternDetector.detectPatterns( files );
		int numVariables = patternDetector.getNumVariables();
		
		
		
		StringBuilder inFileSummarySB = new StringBuilder();
		inFileSummarySB.append( "<html> <h2> Views detected in files </h2>" );
		
		// summary timepoints
		if (multiplicityMap.get( 0 ) == CheckResult.SINGLE)
		{
			inFileSummarySB.append( "<p> No timepoints detected within files </p>" );
			choices.add( "TimePoints" );
		}
		else if (multiplicityMap.get( 0 ) == CheckResult.MULTIPLE_INDEXED)
		{
			int numTPs = accumulateTPMap.keySet().stream().reduce(0, Math::max );
			inFileSummarySB.append( "<p style=\"color:green\">" + numTPs+ " timepoints detected within files </p>" );
			if (accumulateTPMap.size() > 1)
				inFileSummarySB.append( "<p style=\"color:orange\">WARNING: Number of timepoints is not the same for all views </p>" );
		}
		
		inFileSummarySB.append( "<br />" );
		
		// summary channel
		if (multiplicityMap.get( 1 ) == CheckResult.SINGLE)
		{
			inFileSummarySB.append( "<p> No channels detected within files </p>" );
			choices.add( "Channels" );
		}
		else if (multiplicityMap.get( 1 ) == CheckResult.MULTIPLE_INDEXED)
		{
			// TODO: find out number here
			inFileSummarySB.append( "<p > Multiple channels detected within files </p>" );
			inFileSummarySB.append( "<p style=\"color:orange\">WARNING: no metadata was found for channels </p>" );
			if (multiplicityMap.get( 2 ) == CheckResult.MULTIPLE_INDEXED)
			{
				choices.add( "Channels" );
				inFileSummarySB.append( "<p style=\"color:orange\">WARNING: no matadata for Illuminations found either, cannot distinguish </p>" );
				inFileSummarySB.append( "<p style=\"color:orange\">WARNING: choose manually wether files contain channels or illuminations below </p>" );
			}
		} else if (multiplicityMap.get( 1 ) == CheckResult.MUlTIPLE_NAMED)
		{
			int numChannels = accumulateChannelMap.size();
			inFileSummarySB.append( "<p style=\"color:green\">" + numChannels + " Channels found within files </p>" );
		}
		
		inFileSummarySB.append( "<br />" );
		
		// summary illum
		if ( multiplicityMap.get( 2 ) == CheckResult.SINGLE )
		{
			inFileSummarySB.append( "<p> No illuminations detected within files </p>" );
			choices.add( "Illuminations" );
		}
		else if ( multiplicityMap.get( 2 ) == CheckResult.MULTIPLE_INDEXED )
		{
			// TODO: find out number here
			inFileSummarySB.append( "<p > Multiple illuminations detected within files </p>" );
			if (multiplicityMap.get( 1 ).equals( CheckResult.MULTIPLE_INDEXED ))
				choices.add( "Illuminations" );
		}
		else if ( multiplicityMap.get( 2 ) == CheckResult.MUlTIPLE_NAMED )
		{
			int numIllum = accumulateIllumMap.size();
			inFileSummarySB.append( "<p style=\"color:green\">" + numIllum + " Illuminations found within files </p>" );
		}
		
		inFileSummarySB.append( "<br />" );
		
		// summary tile
		if ( multiplicityMap.get( 3 ) == CheckResult.SINGLE )
		{
			inFileSummarySB.append( "<p> No tiles detected within files </p>" );
			choices.add( "Tiles" );
		}
		else if ( multiplicityMap.get( 3 ) == CheckResult.MULTIPLE_INDEXED )
		{
			// TODO: find out number here
			inFileSummarySB.append( "<p > Multiple Tiles detected within files </p>" );
			inFileSummarySB.append( "<p style=\"color:orange\">WARNING: no metadata was found for Tiles </p>" );
			if (multiplicityMap.get( 4 ) == CheckResult.MULTIPLE_INDEXED)
			{
				choices.add( "Tiles" );
				inFileSummarySB.append( "<p style=\"color:orange\">WARNING: no matadata for Angles found either, cannot distinguish </p>" );
				inFileSummarySB.append( "<p style=\"color:orange\">WARNING: choose manually wether files contain Tiles or Angles below </p>" );
			}
		}
		else if ( multiplicityMap.get( 3 ) == CheckResult.MUlTIPLE_NAMED )
		{
			int numTile = accumulateTileMap.size();
			inFileSummarySB.append( "<p style=\"color:green\">" + numTile + " Tiles found within files </p>" );
			
		}
		
		inFileSummarySB.append( "<br />" );
		
		// summary angle
		if ( multiplicityMap.get( 4 ) == CheckResult.SINGLE )
		{
			inFileSummarySB.append( "<p> No angles detected within files </p>" );
			choices.add( "Angles" );
		}
		else if ( multiplicityMap.get( 4 ) == CheckResult.MULTIPLE_INDEXED )
		{
			// TODO: find out number here
			inFileSummarySB.append( "<p > Multiple Angles detected within files </p>" );
			if (multiplicityMap.get( 3 ) == CheckResult.MULTIPLE_INDEXED)
				choices.add( "Angles" );
		}
		else if ( multiplicityMap.get( 4 ) == CheckResult.MUlTIPLE_NAMED )
		{
			int numAngle = accumulateAngleMap.size();
			inFileSummarySB.append( "<p style=\"color:green\">" + numAngle + " Angles found within files </p>" );
		}
		
		inFileSummarySB.append( "</html>" );
		
		GenericDialogPlus gd = new GenericDialogPlus("Assign attributes");
		
		gd.addMessage( "<html> <h1> View assignment </h1> </html> ");
		
		gd.addMessage( inFileSummarySB.toString() );
		
		String[] choicesAngleTile = new String[] {"Angles", "Tiles"};
		String[] choicesChannelIllum = new String[] {"Channels", "Illums"};
				
		
		
		if (ambiguousAngleTile)
			gd.addChoice( "map series to", choicesAngleTile, choicesAngleTile[0] );
		if (ambiguousIllumChannel)
			gd.addChoice( "map channels to", choicesChannelIllum, choicesChannelIllum[0] );
			
		
		StringBuilder sbfilePatterns = new StringBuilder();
		sbfilePatterns.append(  "<html> <h2> Patterns in filenames </h2> " );
		if (numVariables < 1)
			sbfilePatterns.append( "<p> No numerical patterns found in filenames</p>" );
		else
		{
			sbfilePatterns.append( "<p style=\"color:green\"> " + numVariables + " numerical pattern" + ((numVariables > 1) ? "s": "") + " found in filenames</p>" );
			sbfilePatterns.append( "<p> Patterns: " + patternDetector.getStringRepresentation() + "</p>" );
		}
		sbfilePatterns.append( "</html>" );
		
		gd.addMessage( sbfilePatterns.toString() );				
		
		
		
		String[] choicesAll = choices.toArray( new String[]{} );
				
		for (int i = 0; i < numVariables; i++)
			gd.addChoice( "pattern " + i + " assignment", choicesAll, choicesAll[0] );
		
		gd.showDialog();
		
		if (gd.wasCanceled())
			return null;
		
		boolean preferAnglesOverTiles = true;
		boolean preferChannelsOverIlluminations = true;
		if (ambiguousAngleTile)
			preferAnglesOverTiles = gd.getNextChoiceIndex() == 0;
		if (ambiguousIllumChannel)
			preferChannelsOverIlluminations = gd.getNextChoiceIndex() == 0;
		
		for (int i = 0; i < numVariables; i++)
		{
			String choice = gd.getNextChoice();
			if (choice.equals( "TimePoints" ))
				fileVariableToUse.set( 0 , i);
			else if (choice.equals( "Channels" ))
				fileVariableToUse.set( 1 , i);
			else if (choice.equals( "Illuminations" ))
				fileVariableToUse.set( 2 , i);
			else if (choice.equals( "Tiles" ))
				fileVariableToUse.set( 3 , i);
			else if (choice.equals( "Angles" ))
				fileVariableToUse.set( 4 , i);
		}
		
				
		multiplicityMap = FileListDatasetDefinitionUtil.resolveAmbiguity( multiplicityMap, ambiguousIllumChannel, preferChannelsOverIlluminations, ambiguousAngleTile, !preferAnglesOverTiles );
				
		Map< Integer, List< Pair< File, Pair< Integer, Integer > > > > tpIdxMap = new HashMap< >();
		Map< Integer, List< Pair< File, Pair< Integer, Integer > > > > channelIdxMap = new HashMap< >();
		Map< Integer, List< Pair< File, Pair< Integer, Integer > > > > illumIdxMap = new HashMap< >();
		Map< Integer, List< Pair< File, Pair< Integer, Integer > > > > tileIdxMap = new HashMap< >();
		Map< Integer, List< Pair< File, Pair< Integer, Integer > > > > angleIdxMap = new HashMap< >();
		Map< Integer, ChannelInfo > channelDetailMap = new HashMap< >();
		Map< Integer, TileInfo > tileDetailMap = new HashMap< >();
		Map< Integer, AngleInfo > angleDetailMap = new HashMap< >();
		
		FileListDatasetDefinitionUtil.expandAccumulatedViewInfos(
				multiplicityMap,
				fileVariableToUse, 
				patternDetector,
				accumulateTPMap,
				accumulateChannelMap,
				accumulateIllumMap,
				accumulateTileMap,
				accumulateAngleMap,
				tpIdxMap, 
				channelIdxMap,
				illumIdxMap,
				tileIdxMap,
				angleIdxMap,
				channelDetailMap,
				tileDetailMap,
				angleDetailMap );
		
		SpimData2 data = buildSpimData( 
				tpIdxMap,
				channelIdxMap,
				illumIdxMap,
				tileIdxMap,
				angleIdxMap,
				channelDetailMap,
				tileDetailMap,
				angleDetailMap,
				dimensionMap );
		
		
		GenericDialogPlus gdSave = new GenericDialogPlus( "Save dataset definition" );
		
		gdSave.addMessage( "<html> <h1> Saving options </h1> <br /> </html>" );
		
		
		Class imgFactoryClass = ((FileMapImgLoaderLOCI)data.getSequenceDescription().getImgLoader() ).getImgFactory().getClass();
		if (imgFactoryClass.equals( CellImgFactory.class ))
		{
			gdSave.addMessage( "<html> <h2> ImgLib2 container </h2> <br/>"
					+ "<p style=\"color:orange\"> Some views of the dataset are larger than 2^31 pixels, will use CellImg </p>" );
		}
		else
		{
			gdSave.addMessage( "<html> <h2> ImgLib2 container </h2> <br/>");
			String[] imglibChoice = new String[] {"ArrayImg", "CellImg"};
			gdSave.addChoice( "imglib2 container", imglibChoice, imglibChoice[0] );
		}
			
		gdSave.addMessage("<html><h2> Save path </h2></html>");
		
		Set<String> filenames = new HashSet<>();
		((FileMapImgLoaderLOCI)data.getSequenceDescription().getImgLoader() ).getFileMap().values().stream().forEach(
				p -> filenames.add( p.getA().getAbsolutePath()) );
		
		File prefixPath;
		if (filenames.size() > 1)
			prefixPath = getLongestPathPrefix( filenames );
		else
		{
			String fi = filenames.iterator().next();
			prefixPath = new File((String)fi.subSequence( 0, fi.lastIndexOf( File.separator )));
		}
		
		gdSave.addDirectoryField( "dataset save path", prefixPath.getAbsolutePath(), 55 );		
		
		
		gdSave.addCheckbox( "resave as HDF5", false );
		
		gdSave.showDialog();
		
		if ( gdSave.wasCanceled() )
			return null;
		
		if (!imgFactoryClass.equals( CellImgFactory.class ))
		{
			if (gdSave.getNextChoiceIndex() != 0)
				((FileMapImgLoaderLOCI)data.getSequenceDescription().getImgLoader() ).setImgFactory( new CellImgFactory<>(256) );
		}
		
		File chosenPath = new File( gdSave.getNextString());
		data.setBasePath( chosenPath );
		
		boolean resaveAsHDF5 = gdSave.getNextBoolean();
		
		
		if (resaveAsHDF5)
		{
			final Map< Integer, ExportMipmapInfo > perSetupExportMipmapInfo = Resave_HDF5.proposeMipmaps( data.getSequenceDescription().getViewSetupsOrdered() );
			final int firstviewSetupId = data.getSequenceDescription().getViewSetupsOrdered().get( 0 ).getId();
			Generic_Resave_HDF5.lastExportPath = String.join( File.separator, chosenPath.getAbsolutePath(), "dataset");
			final Parameters params = Generic_Resave_HDF5.getParameters( perSetupExportMipmapInfo.get( firstviewSetupId ), true, true );
			
			
			final ProgressWriter progressWriter = new ProgressWriterIJ();
			progressWriter.out().println( "starting export..." );
			
			Generic_Resave_HDF5.writeHDF5( data, params, progressWriter );
			
			System.out.println( "HDF5 resave finished." );
			
			spim.fiji.ImgLib2Temp.Pair< SpimData2, List< String > > result = Resave_HDF5.createXMLObject( data, new ArrayList<>(data.getSequenceDescription().getViewDescriptions().keySet()), params, progressWriter, true );
			
						
			return result.getA();
		}
		
		return data;
		
	}
	
	public static File getLongestPathPrefix(Collection<String> paths)
	{
		String prefixPath = paths.stream().reduce( paths.iterator().next(), 
				(a,b) -> {
					List<String> aDirs = Arrays.asList( a.split( File.separator ));
					List<String> bDirs = Arrays.asList( b.split( File.separator ));
					List<String> res = new ArrayList<>();
					for (int i = 0; i< Math.min( aDirs.size(), bDirs.size() ); i++)
					{
						if (aDirs.get( i ).equals( bDirs.get( i ) ))
							res.add(aDirs.get( i ));
						else {
							break;
						}
					}	
					return String.join( File.separator, res );					
				});
		return new File(prefixPath);
		
	}

	@Override
	public String getTitle() { return "Auto from list of files (LOCI Bioformats)"; }
	
	@Override
	public String getExtendedDescription()
	{
		return "This datset definition tries to automatically detect views in a\n" +
				"list of files openable by BioFormats. \n" +
				"If there are multiple Images in one file, it will try to guess which\n" +
				"views they belong to from meta data or ask the user for advice.\n";
	}


	@Override
	public MultiViewDatasetDefinition newInstance()
	{
		return new FileListDatasetDefinition();
	}
	
	
	public static boolean containsAny(String s, String ... templates)
	{
		for (int i = 0; i < templates.length; i++)
			if (s.contains( templates[i] ))
				return true;
		return false;
	}
	
	public static Pair<String, String> splitIntoPathAndPattern(String s, String ... templates)
	{
		String[] subpaths = s.split( File.separator );
		ArrayList<String> path = new ArrayList<>(); 
		ArrayList<String> pattern = new ArrayList<>();
		boolean noPatternFound = true;
		
		for (int i = 0; i < subpaths.length; i++){
			if (noPatternFound && !containsAny( subpaths[i], templates ))
			{
				path.add( subpaths[i] );
			}
			else
			{
				noPatternFound = false;
				pattern.add(subpaths[i]);
			}
		}
		
		String sPath = String.join( File.separator, path );
		String sPattern = String.join( File.separator, pattern );
		
		return new ValuePair< String, String >( sPath, sPattern );
	}
	
	
	public static void main(String[] args)
	{
		new FileListDatasetDefinition().createDataset();
	}

}