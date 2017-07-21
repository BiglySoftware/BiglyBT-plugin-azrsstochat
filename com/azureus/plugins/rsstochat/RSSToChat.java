/*
 * Created on Dec 11, 2014
 * Created by Paul Gardner
 * 
 * Copyright 2014 Azureus Software, Inc.  All rights reserved.
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or 
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307, USA.
 */


package com.azureus.plugins.rsstochat;

import java.io.*;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.biglybt.core.download.DownloadManager;
import com.biglybt.core.download.DownloadManagerInitialisationAdapter;
import com.biglybt.core.download.DownloadManagerState;
import com.biglybt.core.global.GlobalManager;
import com.biglybt.core.torrent.TOTorrent;
import com.biglybt.core.torrent.TOTorrentCreator;
import com.biglybt.core.torrent.TOTorrentFactory;
import com.biglybt.core.torrent.TOTorrentFile;
import com.biglybt.core.util.AENetworkClassifier;
import com.biglybt.core.util.Base32;
import com.biglybt.core.util.ByteFormatter;
import com.biglybt.core.util.Debug;
import com.biglybt.core.util.DisplayFormatters;
import com.biglybt.core.util.FileUtil;
import com.biglybt.core.util.HashWrapper;
import com.biglybt.core.util.SHA1Simple;
import com.biglybt.core.util.SimpleTimer;
import com.biglybt.core.util.SystemTime;
import com.biglybt.core.util.TimerEvent;
import com.biglybt.core.util.TimerEventPerformer;
import com.biglybt.core.util.TimerEventPeriodic;
import com.biglybt.core.util.TorrentUtils;
import com.biglybt.core.util.UrlUtils;
import com.biglybt.core.xml.util.XUXmlWriter;
import com.biglybt.pif.PluginException;
import com.biglybt.pif.PluginInterface;
import com.biglybt.pif.UnloadablePlugin;
import com.biglybt.pif.logging.LoggerChannel;
import com.biglybt.pif.logging.LoggerChannelListener;
import com.biglybt.pif.torrent.TorrentAttribute;
import com.biglybt.pif.ui.UIManager;
import com.biglybt.pif.ui.components.UITextArea;
import com.biglybt.pif.ui.config.ActionParameter;
import com.biglybt.pif.ui.config.Parameter;
import com.biglybt.pif.ui.config.ParameterListener;
import com.biglybt.pif.ui.model.BasicPluginConfigModel;
import com.biglybt.pif.ui.model.BasicPluginViewModel;
import com.biglybt.pif.utils.LocaleUtilities;
import com.biglybt.pif.utils.search.SearchResult;
import com.biglybt.pif.utils.xml.rss.*;
import com.biglybt.pif.utils.xml.simpleparser.SimpleXMLParserDocument;
import com.biglybt.pif.utils.xml.simpleparser.SimpleXMLParserDocumentAttribute;
import com.biglybt.pif.utils.xml.simpleparser.SimpleXMLParserDocumentNode;
import com.biglybt.pifimpl.local.PluginCoreUtils;
import com.biglybt.pifimpl.local.utils.resourcedownloader.ResourceDownloaderFactoryImpl;
import com.biglybt.ui.swt.Utils;
import com.biglybt.ui.swt.views.utils.ManagerUtils;

import com.biglybt.core.CoreFactory;
import com.biglybt.core.subs.Subscription;
import com.biglybt.core.subs.SubscriptionManager;
import com.biglybt.core.subs.SubscriptionManagerFactory;
import com.biglybt.core.subs.SubscriptionResult;
import com.biglybt.core.tag.Tag;
import com.biglybt.core.tag.TagManagerFactory;
import com.biglybt.core.tag.TagType;
import com.biglybt.core.torrent.PlatformTorrentUtils;
import com.biglybt.plugin.net.buddy.BuddyPluginBeta;
import com.biglybt.plugin.net.buddy.BuddyPluginUtils;
import com.biglybt.plugin.net.buddy.BuddyPluginBeta.ChatInstance;
import com.biglybt.util.MapUtils;

public class 
RSSToChat
	implements UnloadablePlugin
{
	public static final int MAX_MESSAGE_SIZE		= 500;
	public static final int MAX_POSTS_PER_REFRESH	= 10;
	public static final int MAX_HISTORY_ENTRIES		= 1000;
	
	public static final int WEBSITE_RETAIN_SITES_DEFAULT	= 7;
	public static final int WEBSITE_RETAIN_ITEMS_DEFAULT	= 2048;
	
	private TorrentAttribute		ta_website;
	
	private PluginInterface			plugin_interface;
	private LoggerChannel 			log;
	private BasicPluginConfigModel 	config_model;
	private BasicPluginViewModel	view_model;
	private LocaleUtilities			loc_utils;

	private ActionParameter			republish;
	
	private File		config_file;
	private File		history_dir;

	private TimerEventPeriodic	timer;
	
	private List<Mapping>		mappings = new ArrayList<Mapping>();
	
	private boolean	force_site_update;
	
	private boolean	unloaded;
	
	@Override
	public void
	initialize(
		PluginInterface pi )
			
		throws PluginException 
	{
		plugin_interface = pi;
			
		ta_website		= plugin_interface.getTorrentManager().getPluginAttribute( "website" );

		File data_dir = plugin_interface.getPluginconfig().getPluginUserFile( "test" ).getParentFile();
		
		config_file = new File( data_dir, "config.xml" );
		history_dir = new File( data_dir, "history" );
		
		history_dir.mkdirs();
		
		loc_utils = plugin_interface.getUtilities().getLocaleUtilities();
		
		log	= plugin_interface.getLogger().getTimeStampedChannel( "RSSToChat");
		
		final UIManager	ui_manager = plugin_interface.getUIManager();
		
		view_model = ui_manager.createBasicPluginViewModel( loc_utils.getLocalisedMessageText( "azrsstochat.name" ));

		view_model.getActivity().setVisible( false );
		view_model.getProgress().setVisible( false );
									
		config_model = ui_manager.createBasicPluginConfigModel( "plugins", "azrsstochat.name" );

		view_model.setConfigSectionID( "azrsstochat.name" );

		config_model.addLabelParameter2( "azrsstochat.info" );
		
		config_model.addHyperlinkParameter2( "azrsstochat.plugin.link", loc_utils.getLocalisedMessageText( "azrsstochat.plugin.link.url" ));

		ActionParameter	open_dir = config_model.addActionParameter2( "azrsstochat.config.dir.open", "azrsstochat.open" );

		open_dir.addListener(
				new ParameterListener() 
				{
					@Override
					public void
					parameterChanged(
						Parameter param ) 
					{
						Utils.launch( config_file.getParentFile().getAbsolutePath());
					}
				});
		ActionParameter	reload_param = config_model.addActionParameter2( "azrsstochat.config.reload", "azrsstochat.reload" );
		
		reload_param.addListener(
			new ParameterListener() 
			{
				@Override
				public void
				parameterChanged(
					Parameter param ) 
				{
					synchronized( mappings ){
					
						loadConfig();
					}
				}
			});
		
		republish = config_model.addActionParameter2( "azrsstochat.config.republish", "azrsstochat.republish" );

		republish.addListener(
				new ParameterListener() 
				{
					@Override
					public void
					parameterChanged(
						Parameter param ) 
					{
						republish.setEnabled( false );
						
						force_site_update = true;
					}
				});

		
		final UITextArea text_area = config_model.addTextArea( "azrsstochat.statuslog");
		
		log.addListener(
				new LoggerChannelListener()
				{
					@Override
					public void
					messageLogged(
						int		type,
						String	content )
					{
						view_model.getLogArea().appendText( content + "\n" );
						
						text_area.appendText( content + "\n" );
					}
					
					@Override
					public void
					messageLogged(
						String		str,
						Throwable	error )
					{
						view_model.getLogArea().appendText( str + "\n" );
						view_model.getLogArea().appendText( error.toString() + "\n" );
						
						String text = str + ": " + Debug.getNestedExceptionMessage( error );
						
						text_area.appendText( text + "\n" );

					}
				});
				
		synchronized( mappings ){
			
			loadConfig();

			if ( unloaded ){
				
				return;
			}
			
			timer = 
				SimpleTimer.addPeriodicEvent(
					"RSSToChat",
					60*1000,
					new TimerEventPerformer()
					{	
						private int		minute_count;
					
						@Override
						public void
						perform(
							TimerEvent event ) 
						{
							BuddyPluginBeta bp = BuddyPluginUtils.getBetaPlugin();
							
							if ( bp == null || !bp.isInitialised()){
								
								log( "Decentralized chat not available (yet)" );
								
								return;
							}
							
							minute_count++;
													
							List<Mapping>	maps;
							
							synchronized( mappings ){
								
								maps = new ArrayList<Mapping>( mappings );
							}
							
							for ( Mapping map: maps ){
								
								map.update( bp, minute_count, force_site_update );
							}
							
							force_site_update = false;
							
							republish.setEnabled( true );
						}
					});
		}
	}
	
	private void
	log(
		String		str )
	{
		log.log( str);
	}
	
	private void
	log(
		String		str,
		Throwable 	e )
	{
		log.log( str, e );
	}
	
	private void
	loadConfig()
	{
		log( "Loading configuration" );
		
		if ( !config_file.exists()){
			
			try{
				PrintWriter pw = new PrintWriter( new OutputStreamWriter( new FileOutputStream( config_file ), "UTF-8" ));
			
				String NL = "\r\n";
				
				pw.println(
					"<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + NL +
					"<!-- See http://wiki.vuze.com/w/RSS_To_Chat -->" + NL +
					"<config>" + NL +
					"</config>");

				pw.close();
				
			}catch( Throwable e ){
			
				log( "Failed to create default configuration", e );
			}
		}
		
		List<Mapping>	loaded_mappings = new ArrayList<Mapping>();
		
		try{
			SubscriptionManager subs_man = SubscriptionManagerFactory.getSingleton();
			
			SimpleXMLParserDocument doc = plugin_interface.getUtilities().getSimpleXMLParserDocumentFactory().create( config_file );
			
			SimpleXMLParserDocumentNode[] kids = doc.getChildren();
			
			int	num_mappings = 0;
			
			for ( SimpleXMLParserDocumentNode kid: kids ){
				
				num_mappings++;
				
				log( "    Processing mapping " + num_mappings );
				
				String kid_name = kid.getName();
				
				if ( !kid_name.equalsIgnoreCase("mapping")){
					
					throw( new Exception( "<mapping> element expected, got " + kid_name  ));
				}
				
				SimpleXMLParserDocumentNode rss_node 			= kid.getChild( "rss" );
				SimpleXMLParserDocumentNode subs_node 			= kid.getChild( "subscription" );
				SimpleXMLParserDocumentNode chat_node 			= kid.getChild( "chat" );
				SimpleXMLParserDocumentNode presentation_node 	= kid.getChild( "presentation" );
				SimpleXMLParserDocumentNode refresh_node 		= kid.getChild( "refresh" );
				SimpleXMLParserDocumentNode associations_node 	= kid.getChild( "associations" );
				
				String 	source;
				boolean	is_rss;
				
				Pattern	desc_link_pattern 	= null;
				String	link_type			= "magnet";
				boolean	ignore_dates		= false;
				int		min_seeds			= 0;
				int		min_leechers		= 0;
				
				String	presentation 			= "link";
				String	website_name			= null;
				int		website_retain_sites	= WEBSITE_RETAIN_SITES_DEFAULT;
				int		website_retain_items	= WEBSITE_RETAIN_ITEMS_DEFAULT;
				
				List<Subscription>	item_associations = new ArrayList<Subscription>();
				
				if ( rss_node != null && subs_node == null ){
					
					SimpleXMLParserDocumentNode url_node = rss_node.getChild( "url" );
					
					if ( url_node == null ){
						
						throw( new Exception( "<rss> must contain a <url> entry" ));
					}

					String url_str = url_node.getValue().trim();
					
					try{
						URL url = new URL( url_str );
						
					}catch( Throwable e ){
						
						throw( new Exception( "<url> value '" + url_str + "' is invalid" ));
					}
					
					SimpleXMLParserDocumentNode desc_link_node = rss_node.getChild( "dl_link_pattern" );

					if ( desc_link_node != null ){
						
						try{
							desc_link_pattern = Pattern.compile(desc_link_node.getValue().trim(), Pattern.CASE_INSENSITIVE );

						}catch( Throwable e ){
						
							throw( new Exception( "<dl_link_pattern> value '" + desc_link_pattern + "' is invalid", e ));
						}
					}
					source 	= url_str;
					is_rss	= true;
					
				}else if ( subs_node != null && rss_node == null ){
					
					SimpleXMLParserDocumentNode name_node = subs_node.getChild( "name" );
					
					if ( name_node == null ){
						
						throw( new Exception( "<subscription> must contain a <name> entry" ));
					}

					String name = name_node.getValue().trim();
					
					SimpleXMLParserDocumentNode link_type_node = subs_node.getChild( "link_type" );

					if ( link_type_node != null ){
													
						link_type = link_type_node.getValue().trim().toLowerCase( Locale.US );
						
						if ( link_type.equals( "hash" ) || link_type.equals( "details_url") || link_type.equals( "download_url")){
							
						}else{
						
							throw( new Exception( "<link_type> value '" + link_type + "' is invalid" ));
						}
					}
					
					SimpleXMLParserDocumentNode ignore_dates_node = subs_node.getChild( "ignore_dates" );

					if ( ignore_dates_node != null ){
						
						String id_value = ignore_dates_node.getValue().trim();

						ignore_dates = id_value.equalsIgnoreCase( "true" );
					}
					
					SimpleXMLParserDocumentNode min_seeds_node = subs_node.getChild( "minimum_seeds" );

					if ( min_seeds_node != null ){
						
						String value = min_seeds_node.getValue().trim();

						min_seeds = Integer.parseInt( value );
					}
					
					SimpleXMLParserDocumentNode min_leechers_node = subs_node.getChild( "minimum_leechers" );

					if ( min_leechers_node != null ){
						
						String value = min_leechers_node.getValue().trim();

						min_leechers = Integer.parseInt( value );
					}
					
					source 	= name;
					is_rss	= false;
				}else{
					
					throw( new Exception( "<mapping> must contain either an <rss> or a <subscription> entry" ));

				}
					
				if ( chat_node == null ){
					
					throw( new Exception( "<mapping> must contain a <chat> entry" ));
				}
				
				SimpleXMLParserDocumentNode net_node 	= chat_node.getChild( "network" );
				SimpleXMLParserDocumentNode key_node 	= chat_node.getChild( "key" );
				SimpleXMLParserDocumentNode type_node 	= chat_node.getChild( "type" );
				SimpleXMLParserDocumentNode nick_node 	= chat_node.getChild( "nick" );

				if ( net_node == null || key_node == null ){
					
					throw( new Exception( "<chat> must contain a <network> and a <key> entry" ));
				}
				
				String network_str = net_node.getValue().trim();
				
				String[] networks;
				
				if ( network_str.equalsIgnoreCase( "public" )){
					
					networks = new String[]{ AENetworkClassifier.AT_PUBLIC };
					
				}else if ( network_str.equalsIgnoreCase( "anonymous" )){
					
					networks = new String[]{ AENetworkClassifier.AT_I2P };

				}else if ( network_str.equalsIgnoreCase( "both" )){
					
					networks = new String[]{ AENetworkClassifier.AT_PUBLIC, AENetworkClassifier.AT_I2P };
					
				}else{
					
					throw( new Exception( "<network> must be either 'public', 'anonymous' or 'both'" ));
				}
				
				String key = key_node.getValue().trim();
				
				if ( 	key.startsWith( "Tag:" ) || 
						key.startsWith( "Download:" ) || 
						//key.startsWith( "Vuze:" ) || 
						key.startsWith( "General:" ) || 
						key.startsWith( "Announce:" )){
					
					throw( new Exception( "Invalid key name '" + key + "', select something else" ));
				}
				
				if ( refresh_node == null ){
					
					throw( new Exception( "<mapping> must contain a <refresh> entry" ));
				}

				String refresh_str = refresh_node.getValue().trim();
				
				int	refresh_mins;
				
				try{
					refresh_mins = Integer.parseInt( refresh_str );
							
				}catch( Throwable e ){
					
					throw( new Exception( "<refresh> value of '" + refresh_str + "' is invalid" ));

				}
				
				int	type = Mapping.TYPE_NORMAL;
				
				if ( type_node != null ){
					
					String type_str = type_node.getValue().trim();
					
					if ( type_str.equalsIgnoreCase( "normal" )){
						
					}else if ( type_str.equalsIgnoreCase( "readonly" )){
						
						type = Mapping.TYPE_READ_ONLY;
						
					}else if ( type_str.equalsIgnoreCase( "admin" )){
					
						type = Mapping.TYPE_ADMIN;
						
					}else{
						
						throw( new Exception( "<type> value of '" + type_str + "' is invalid" ));

					}
				}
				
				String nick = null;
				
				if ( nick_node != null ){
					
					nick = nick_node.getValue().trim();
					
					if ( nick.length() == 0 ){
						
						nick = null;
					}
				}
				
				if ( presentation_node != null ){
					
					SimpleXMLParserDocumentNode p_type_node = presentation_node.getChild( "type" );
					
					if ( p_type_node == null ){
						
						throw( new Exception( "presentation node requires <type> child" ));
					}
					
					String p_type = p_type_node.getValue().trim();
					
					if ( p_type.equals( "link" )){
						
						presentation = "link";
						
					}else if ( p_type.equals( "website" )){
											
						presentation = "website";
						
					}else{
						
						throw( new Exception( "presentation <type> value of '" + p_type + "' is invalid" ));
					}
					
					SimpleXMLParserDocumentNode p_name_node = presentation_node.getChild( "name" );
					
					if ( p_name_node != null ){
						
						website_name = p_name_node.getValue().trim();
					}
					
					SimpleXMLParserDocumentNode p_sites_node = presentation_node.getChild( "retain_sites" );

					if ( p_sites_node != null ){
						
						try{
							website_retain_sites = Integer.parseInt( p_sites_node.getValue().trim());
							
						}catch( Throwable e ){
							
							throw( new Exception( "presentation <retain_sites> value of '" + p_sites_node.getValue() + "' is invalid" ));

						}
					}
					
					SimpleXMLParserDocumentNode p_items_node = presentation_node.getChild( "retain_items" );

					if ( p_items_node != null ){
						
						try{
							website_retain_items = Integer.parseInt( p_items_node.getValue().trim());
							
						}catch( Throwable e ){
							
							throw( new Exception( "presentation <retain_items> value of '" + p_items_node.getValue() + "' is invalid" ));

						}
					}
				}
				
				if ( associations_node != null ){
					
					SimpleXMLParserDocumentNode[] associations = associations_node.getChildren();
					
					for ( SimpleXMLParserDocumentNode association: associations ){
						
						SimpleXMLParserDocumentNode name_node = association.getChild( "name" );
						
						if ( name_node == null ){
							
							throw( new Exception( "association name missing" ));
						}
						
						String name = name_node.getValue().trim();
						
						Subscription[] subs = subs_man.getSubscriptions();
						
						boolean found = false;
						
						for ( Subscription sub: subs ){
							
							if ( sub.getName().equals( name )){
								
								item_associations.add( sub );
								
								found = true;
								
								break;
							}
						}
						
						if ( !found ){
							
							throw( new Exception( "subscription '" + name + "' not found" ));
						}
					}
				}
				for ( String network: networks ){
					
					Mapping mapping = new Mapping( source, is_rss, desc_link_pattern, link_type, ignore_dates, min_seeds, min_leechers, network, key, type, nick, presentation, website_name, website_retain_sites, website_retain_items, item_associations, refresh_mins );
					
					log( "    Mapping: " + mapping.getOverallName());
					
					loaded_mappings.add( mapping );
				}
			}
		
			
		}catch( Throwable e ){
			
			log( "Failed to parse configuration file: " + Debug.getNestedExceptionMessage( e ));
		}
		
		log( "Configuration loaded" );

		synchronized( mappings ){
			
			for ( Mapping mapping: mappings ){
				
				mapping.destroy();
			}
			
			mappings.clear();
			
			mappings.addAll ( loaded_mappings );
		}
	}
	
	private String
	extractLinkFromDescription(
		Pattern		pattern,
		String		value )
	{
		String	desc_dl_link = null;
		String	desc_fl_link = null;
				
		Matcher m = pattern.matcher( value );
								
		while( m.find()){
			
			String desc_url_str = m.group(1);
			
			desc_url_str = XUXmlWriter.unescapeXML( desc_url_str );
			
			desc_url_str = UrlUtils.decode( desc_url_str );
			
			String lc_desc_url_str = desc_url_str.toLowerCase();
			
			try{
				URL desc_url = new URL(desc_url_str);

				if ( lc_desc_url_str.startsWith( "magnet:" )){
					
					desc_dl_link = desc_url.toExternalForm();
					
				}else{
					
					if ( lc_desc_url_str.contains( ".torrent" )){
						
						desc_fl_link = desc_url.toExternalForm();
					}
				}
			}catch( Throwable e ){
				
			}
		}
		
		if ( desc_fl_link != null ){
			
			return( desc_fl_link );
		}
		
		return( desc_dl_link );
	}
	
	private boolean
	updateRSS(
		Mapping			mapping,
		String			rss_source,
		ChatInstance	inst,
		History			history,
		boolean			force )
	{
		boolean	try_again = false;
		
		try{
			RSSFeed feed = plugin_interface.getUtilities().getRSSFeed( new URL( rss_source ));
			
			RSSChannel channel = feed.getChannels()[0];
			
			RSSItem[] items = channel.getItems();
			
			log( "    RSS '" + rss_source + "' returned " + items.length + " total items" );
			
			final Map<RSSItem, Integer> item_map = new HashMap<RSSItem, Integer>();
			
			for ( int i=0;i<items.length;i++ ){
				
				item_map.put( items[i], i );
			}
			
			Arrays.sort( 
				items,
				new Comparator<RSSItem>()
				{
					@Override
					public int
					compare(
						RSSItem i1,
						RSSItem i2) 
					{
						Date d1 = i1.getPublicationDate();
						Date d2 = i2.getPublicationDate();
						
						long res = (d1==null?0:d1.getTime()) - (d2==null?0:d2.getTime());
						
						if ( res < 0 ){
							return( -1 );
						}else if ( res > 0 ){
							return( 1 );
						}else{
							return( item_map.get( i1 ) - item_map.get( i2 ));
						}
					}
				});
			
			int	posted = 0;
					
			boolean presentation_is_link = mapping.getPresentation().equals( "link" );

			boolean	site_updated = force;
			
			for ( RSSItem item: items ){
				
				Date 	item_date = item.getPublicationDate();
				
				long	item_time = item_date==null?0:item_date.getTime();
				
				if ( item_time > 0 && item_time < history.getLatestPublish()){
					
						// allow out-of-order item addition for website presentations they may well be
						// assembled from downloads added at varying times
					
					if ( presentation_is_link ){
					
						continue;
					}
				}
				
				String title = item.getTitle();
				
				String title_short = title;
				
				if ( title_short.length() > 80 ){
					
					title_short = title_short.substring( 0, 80 ) + "...";
				}
				
				String 	hash 		= "";
				String	dl_link 	= null;
				String	cdp_link 	= null;
				String	thumb_link	= null;
				
				String	description		= null;
				String	desc_dl_link	= null;

				long	size 		= -1;
				long	seeds		= -1;
				long	leechers	= -1;
				
				SimpleXMLParserDocumentNode node = item.getNode();
				
				SimpleXMLParserDocumentNode[] kids = node.getChildren();
				
				for ( SimpleXMLParserDocumentNode child: kids ){
					
					String	lc_child_name 		= child.getName().toLowerCase();
					String	lc_full_child_name 	= child.getFullName().toLowerCase();
					
					String	value = child.getValue();
					
					if (lc_child_name.equals( "enclosure" )){
						
						SimpleXMLParserDocumentAttribute typeAtt = child.getAttribute("type");
						
						if( typeAtt != null && typeAtt.getValue().equalsIgnoreCase( "application/x-bittorrent")) {
							
							SimpleXMLParserDocumentAttribute urlAtt = child.getAttribute("url");
							
							if( urlAtt != null ){
								
								dl_link = urlAtt.getValue();
							}
							
							SimpleXMLParserDocumentAttribute lengthAtt = child.getAttribute("length");
							
							if (lengthAtt != null){
								
								try{
									size = Long.parseLong(lengthAtt.getValue().trim());
									
								}catch( Throwable e ){
								}	
							}
						}
			
					}else if ( lc_child_name.equals( "link" ) || lc_child_name.equals( "guid" )){
						
						String lc_value = value.toLowerCase();
														
						try{
							URL url = new URL(value);

							if ( 	lc_value.endsWith( ".torrent" ) ||
									lc_value.startsWith( "magnet:" ) ||
									lc_value.startsWith( "bc:" ) ||
									lc_value.startsWith( "bctp:" ) ||
									lc_value.startsWith( "dht:" )){
								
								
								dl_link = value;
								
							}else{
								
								cdp_link = value;
							}
						}catch( Throwable e ){
							
								// see if this is an atom feed 
								//  <link rel="alternate" type="application/x-bittorrent" href="http://asdasd/ 
							
							SimpleXMLParserDocumentAttribute typeAtt = child.getAttribute( "type" );
							
							if ( typeAtt != null && typeAtt.getValue().equalsIgnoreCase("application/x-bittorrent")) {
							
								SimpleXMLParserDocumentAttribute hrefAtt = child.getAttribute( "href" );
								
								if ( hrefAtt != null ){
									
									String	href = hrefAtt.getValue().trim();
									
									try{
										
										dl_link = new URL( href ).toExternalForm();
										
									}catch( Throwable f ){
										
									}
								}
							}
						}
					}else if ( lc_child_name.equals( "content" ) && feed.isAtomFeed()){
						
						SimpleXMLParserDocumentAttribute srcAtt = child.getAttribute( "src" );
						
						String	src = srcAtt==null?null:srcAtt.getValue();
									
						if ( src != null ){
							
							boolean	is_dl_link = false;
							
							SimpleXMLParserDocumentAttribute typeAtt = child.getAttribute( "type" );
							
							if ( typeAtt != null && typeAtt.getValue().equalsIgnoreCase("application/x-bittorrent")) {

								is_dl_link = true;
							}
							
							if ( !is_dl_link ){
							
								is_dl_link = src.toLowerCase().indexOf( ".torrent" ) != -1;
							}
								
							if ( is_dl_link ){
								
								try{
									new URL( src );
								
									dl_link = src;
									
								}catch( Throwable e ){
								}
							}
						}
						
					}else if ( lc_child_name.equals( "description" )){
								
						description = value;
						
						Pattern pattern = mapping.desc_link_pattern;
						
						if ( pattern != null ){
						
							desc_dl_link = extractLinkFromDescription( pattern, description );
						}
					}else if ( lc_full_child_name.equals( "vuze:size" )){
						
						try{
							size = Long.parseLong( value );
							
						}catch( Throwable e ){
						}
					}else if ( lc_full_child_name.equals( "vuze:seeds" ) || lc_full_child_name.equals( "torrent:seeds" )){
						
						try{
							seeds = Long.parseLong( value );
							
						}catch( Throwable e ){
						}
					
					}else if ( lc_full_child_name.equals( "vuze:peers" ) || lc_full_child_name.equals( "torrent:peers" )){
						
						try{
							leechers = Long.parseLong( value );
							
						}catch( Throwable e ){
						}
						
					
						
					}else if ( lc_full_child_name.equals( "vuze:downloadurl" )){

						dl_link = value;
						
					}else if ( lc_full_child_name.equals( "vuze:assethash" )){

						hash = value;	// base32
						
					}else if ( lc_full_child_name.equals( "torrent:infoHash" )){

						hash = value;	// base 16, hmmmm, be consistent
						
						if ( hash.length() == 40 ){
							
							hash = Base32.encode( ByteFormatter.decodeString( hash ));
						}
					}else if (  lc_full_child_name.equals( "media:thumbnail" )){
						
						thumb_link = child.getAttribute( "url" ).getValue().trim();
					}
				}
				
				if ( desc_dl_link != null ){
				
					if ( cdp_link == null && dl_link != null && !desc_dl_link.equals( dl_link )){
						
						cdp_link = dl_link;
					}
					
					dl_link = desc_dl_link;
				}
				
				if ( hash == "" && dl_link == null ){
					
					continue;
				}
					
				int min_seeds = mapping.getMinSeeds();
				
				if ( min_seeds > 0 && seeds < min_seeds ){
					
					continue;
				}

				int min_leechers = mapping.getMinLeechers();
				
				if ( min_leechers > 0 && leechers < min_leechers ){
					
					continue;
				}
				
				
				String magnet = buildMagnetHead( dl_link, cdp_link, hash, title_short );
				
				String history_key = magnet;
				
					// unfortunately if the magnet link is generated from a download with a lot of webseeds then a limited and randomized subset of
					// these are returned by Vuze - this results in the same download generating multiple distinct magnets which, without processing, 
					// causes repeat entries in the feed. Solution is to remove &ws= entries if there are more than 4 (for some backward compatability)
				
				String history_key_no_ws = "";
				
				String[] bits = history_key.split( "&" );
				
				int	num_ws = 0;
				
				for ( String bit: bits ){
				
					if ( bit.toLowerCase( Locale.US ).startsWith( "ws=" )){
						
						num_ws++;
						
					}else{
						
						history_key_no_ws += (history_key_no_ws.length()==0?"":"&") + bit;
					}
				}
				
				if ( num_ws > 4 ){
					
					history_key = history_key_no_ws;
				}
				
				if ( history != null && history.hasPublished( history_key )){
					
					continue;
				}
				
				if ( presentation_is_link ){
					
					magnet = buildMagnetTail(magnet, dl_link, cdp_link, title_short, size, item_time, seeds, leechers );
					
					inst.sendMessage( magnet, new HashMap<String, Object>());
					
					history.setPublished( history_key, item_time );
					
					posted++;
					
					if ( posted >= MAX_POSTS_PER_REFRESH ){
						
						try_again = true;
						
						break;
					}
				}else{
					
					extractSiteItem(
						history, history_key, item_time,
						title, description, hash, size,
						seeds, leechers,
						dl_link, cdp_link, thumb_link );
						
					site_updated = true;
				}
				
				checkItemAssociations( mapping, hash );
			}
			
			if ( presentation_is_link ){
				
				log( "    Posted " + posted + " new results" );

			}else{
				
				if ( site_updated ){
					
					updateSite( mapping, inst, channel.getTitle(), history );
				}
			}
		}catch( Throwable e ){
			
			try_again = true;
			
			log( "RSS update for " + rss_source + " failed", e );
		}
		
		return( try_again );
	}	
	
	private boolean
	updateSubscription(
		Mapping			mapping,
		String			subscription_name,
		ChatInstance	chat,
		History			history,
		boolean			force )
	{
		boolean	try_again 		= false;
		boolean	site_updated	= force;
		
		Subscription[] subscriptions = SubscriptionManagerFactory.getSingleton().getSubscriptions();
		
		boolean presentation_is_link = mapping.getPresentation().equals( "link" );

		boolean	subs_found = false;
		
		for ( Subscription sub: subscriptions ){
			
			if ( !sub.isSubscribed()){
				
				continue;
			}
			
			String sub_name = sub.getName();
			
			if ( sub_name.equals( subscription_name )){
				
				subs_found = true;
				
				final Map<SubscriptionResult, Map<Integer,Object>>	result_map = new HashMap<SubscriptionResult, Map<Integer,Object>>();
								
				SubscriptionResult[] all_results = sub.getResults( false );
				
				for ( SubscriptionResult result: all_results ){
					
					String history_key = result.getID();
					
					if ( history.hasPublished( history_key )){
						
						continue;
					}
					
					result_map.put( result, result.toPropertyMap());
				}
				
				Map<SubscriptionResult, Map<Integer,Object>> sorted_result_map = 
					new TreeMap<SubscriptionResult, Map<Integer,Object>>(
							new Comparator<SubscriptionResult>()
							{
								@Override
								public int
								compare(
									SubscriptionResult r1,
									SubscriptionResult r2 ) 
								{
									Map<Integer,Object> p1 = result_map.get( r1 );
									Map<Integer,Object> p2 = result_map.get( r2 );
									
									Date 	pub_date1 	= (Date)p1.get( SearchResult.PR_PUB_DATE );

									long	result_time1 = pub_date1==null?0:pub_date1.getTime();
									
									Date 	pub_date2 	= (Date)p2.get( SearchResult.PR_PUB_DATE );

									long	result_time2 = pub_date2==null?0:pub_date2.getTime();
									
									if ( result_time1 < result_time2 ){
										
										return( -1 );
										
									}else if ( result_time1 > result_time2 ){
										
										return( 1 );
										
									}else{
										
										return( r1.getID().compareTo(r2.getID()));
									}

								}
							});
					
				sorted_result_map.putAll( result_map );
				
				log( "    Subscription '" + subscription_name + "' returned " + all_results.length + " total results" );

				int	posted = 0;
				
				for ( Map.Entry<SubscriptionResult, Map<Integer,Object>> entry: sorted_result_map.entrySet()){
					
					SubscriptionResult	result = entry.getKey();
					
					String history_key = result.getID();
					
					Map<Integer,Object>	props = entry.getValue();
									
					long	seeds		= (Long)props.get( SearchResult.PR_SEED_COUNT );

					int min_seeds = mapping.getMinSeeds();
					
					if ( min_seeds > 0 && seeds < min_seeds ){
						
						continue;
					}

					long	leechers	= (Long)props.get( SearchResult.PR_LEECHER_COUNT );

					int min_leechers = mapping.getMinLeechers();
					
					if ( min_leechers > 0 && leechers < min_leechers ){
						
						continue;
					}
					
					//System.out.println( history_key + " -> " + result.toPropertyMap());
					
					String title = (String)props.get( SearchResult.PR_NAME );
					
					if ( title.length() > 80 ){
						
						title = title.substring( 0, 80 ) + "...";
					}
					
					Date 	pub_date 	= (Date)props.get( SearchResult.PR_PUB_DATE );

					long	result_time = pub_date==null?0:pub_date.getTime();
					
					if ( !mapping.getIgnoreDates()){
						
						if ( result_time > 0 && result_time < history.getLatestPublish()){
							
							continue;
						}
					}
					
					byte[] 	b_hash 		= (byte[])props.get( SearchResult.PR_HASH );
					
					String hash = b_hash==null?"":Base32.encode(b_hash);
					
					String	dl_link 	= (String)props.get( SearchResult.PR_TORRENT_LINK );
					
					if ( dl_link == null ){
						
						dl_link 	= (String)props.get( SearchResult.PR_DOWNLOAD_LINK );
					}
					
					String	cdp_link 	= (String)props.get( SearchResult.PR_DETAILS_LINK );
					
					long	size 		= (Long)props.get( SearchResult.PR_SIZE );
						
					if ( mapping.link_type.equals( "details_url" ) || mapping.link_type.equals( "download_url" )){
						
						if ( mapping.link_type.equals( "details_url" )){
							
							if ( cdp_link == null ){
								
								continue;
							}
							
							dl_link = null;
							
						}else{
							
							if ( dl_link == null ){
								
								continue;
							}
						}
						
						if ( presentation_is_link ){
							
							String magnet = buildMagnetHead( dl_link, cdp_link, "", title );
						
							magnet = buildMagnetTail(magnet, dl_link, cdp_link, title, size, result_time, seeds, leechers );
							
							chat.sendMessage( magnet, new HashMap<String, Object>());
						
							history.setPublished( history_key, result_time );
							
							posted++;
							
							if ( posted >= MAX_POSTS_PER_REFRESH ){
								
								try_again = true;
								
								break;
							}
						}else{
							
							extractSiteItem(
									history, history_key, result_time,
									title, null, hash, size, seeds, leechers,
									dl_link, cdp_link, null );
									
							site_updated = true;
						}
					}else{
						
						if ( hash == "" && dl_link == null ){
							
							continue;
						}
							
						if ( presentation_is_link ){
														
							String magnet = buildMagnetHead( dl_link, cdp_link, hash, title );
							
							magnet = buildMagnetTail(magnet, dl_link, cdp_link, title, size, result_time, seeds, leechers );
							
							chat.sendMessage( magnet, new HashMap<String, Object>());
							
							history.setPublished( history_key, result_time );
							
							posted++;
							
							if ( posted >= MAX_POSTS_PER_REFRESH ){
								
								try_again = true;
								
								break;
							}
						}else{
							
							extractSiteItem(
									history, history_key, result_time,
									title, null, hash, size, seeds, leechers,
									dl_link, cdp_link, null );
									
							site_updated = true;
						}
					}
					
					checkItemAssociations( mapping, hash );
				}
				
				if ( presentation_is_link ){
					
					log( "    Posted " + posted + " new results" );

				}else{
					
					if ( site_updated ){
						
						updateSite( mapping, chat, subscription_name, history );
					}
				}
			}
		}
		
		if ( !subs_found ){
			
			log( "Subscription '" + subscription_name + "' not found" );
		}
		
		return( try_again );
	}
	
	
	
	private void
	extractSiteItem(
		History		history,
		String		history_key,
		long		item_time,
		String		title,
		String		description,
		String		hash_str,
		long		size,
		long		seeds,
		long		leechers,
		String		dl_link,
		String		cdp_link,
		String		thumb_link )
	{
		File item_folder = history.getItemFolder( history_key, item_time );
			
		String item_key = Base32.encode( getKey( history_key ));
		
		String	torrent_file_name 	= null;
		String	thumb_file_name 	= null;
		
		if ( dl_link != null ){
			
			try{
				File torrent_file = null;
				
				URL dl_url = new URL( dl_link );
				
				String protocol = dl_url.getProtocol();
				
				if ( protocol.equals( "magnet" )){
					
					String query = dl_url.getQuery();
					
					if ( query != null ){
						
						String[] args = query.split( "&" );
						
						for ( String arg: args ){
							
							String[] temp = arg.split( "=" );
							
							if ( temp[0].equals( "fl" )){
								
								dl_url = new URL( UrlUtils.decode( temp[1] ));
								
								torrent_file_name = item_key + ".torrent";
								
								File tf = new File( item_folder, torrent_file_name );
							
								if ( !tf.exists()){
							
									FileUtil.copyFile( new ResourceDownloaderFactoryImpl().create( dl_url ).download(), tf );
							
									log( "Downloaded torrent: " + dl_url );
									
									torrent_file = tf;
								}
							}
						}
					}
				}else{
					
					String path 	= dl_url.getPath();
					
					if ( protocol.startsWith( "http" ) && path.endsWith( ".torrent" )){
					
						torrent_file_name = item_key + ".torrent";
						
						File tf = new File( item_folder, torrent_file_name );
					
						if ( !tf.exists()){
					
							FileUtil.copyFile( new ResourceDownloaderFactoryImpl().create( dl_url ).download(), tf );
					
							log( "Downloaded torrent: " + dl_url );
							
							torrent_file = tf;
						}
					}
				}
				
				if ( hash_str == null || hash_str.length() == 0 ){
					
					if ( torrent_file != null ){
						
						try{
							byte[] b_hash = TOTorrentFactory.deserialiseFromBEncodedFile( torrent_file ).getHash();
							
							hash_str = ByteFormatter.encodeString( b_hash );
							
						}catch( Throwable e ){
							
							log( "Invalid torrent: " + dl_url );
						}
					}
				}
			}catch( Throwable e ){
				
				log( "Failed to download torrent: " + thumb_link, e );
				
				return;
			}
		}

		if ( thumb_link != null ){
			
			try{
				URL thumb_url = new URL( thumb_link );
				
				String path = thumb_url.getPath();
				
				int pos = path.lastIndexOf( "." );
				
				String ext;
				
				if ( pos != -1 ){
					
					ext = path.substring( pos+1 );
					
				}else{
					
					ext = "jpg";
				}
				
				thumb_file_name = item_key + "." + ext;
				
				File thumb_file = new File( item_folder, thumb_file_name );
				
				if ( !thumb_file.exists()){
				
					FileUtil.copyFile( new ResourceDownloaderFactoryImpl().create( thumb_url ).download(), thumb_file);
				
					log( "Download thumb: " + thumb_url );
				}
			}catch( Throwable e ){
				
				log( "Failed to download thumb: " + thumb_link, e );
				
				return;
			}
		}
		
		File item_config = new File( item_folder, "item.config" );
		
		if ( !item_config.exists()){
			
			Map<String,Object>	map = new HashMap<String, Object>();
			
			map.put( "title", title );
			map.put( "time", item_time );
			map.put( "description", description );
			map.put( "hash", hash_str );
			map.put( "dl_link", dl_link );
			map.put( "cdp_link", cdp_link );
			map.put( "size", size );
			map.put( "seeds", seeds );
			map.put( "leechers", leechers );
			
			if ( thumb_file_name != null ){
				
				map.put( "thumb", thumb_file_name );
			}
			
			if ( torrent_file_name != null ){
				
				map.put( "torrent", torrent_file_name );
			}
			
			FileUtil.writeResilientFile( item_config, map );
		}
							
		history.setPublished( history_key, item_time );
	}
	
	private void
	updateSite(
		final Mapping	mapping,
		ChatInstance	inst,
		String			channel_title,
		History			history )
	{
		File items_folder = history.getItemsFolder();

		File[] items = items_folder.listFiles();
		
		Arrays.sort(
			items,
			new Comparator<File>()
			{
				@Override
				public int
				compare(
					File file1, 
					File file2) 
				{
					String name1 = file1.getName();
					String name2 = file2.getName();
					
					long t1 = Long.parseLong( name1.substring( name1.lastIndexOf( "_" ) + 1 ));
					long t2 = Long.parseLong( name2.substring( name2.lastIndexOf( "_" ) + 1 ));
					
					if ( t1 < t2 ){
						return( 1 );
					}else if ( t1 > t2 ){
						return( -1 );
					}else{
						return( name2.compareTo( name1 ));
					}
				}		
			});
		
		long	now = SystemTime.getCurrentTime();
		
		File site_folder = history.getSiteFolder( now );
		
		site_folder.mkdirs();
		
		File from_resources = new File( plugin_interface.getPluginDirectoryName(), "resources" );
		File to_resources 	= site_folder;
				
		try{
			List<String>	associations_to_check = new ArrayList<String>();
			
			FileUtil.copyFileOrDirectory( from_resources, to_resources ); 

			PrintWriter index = new PrintWriter( new OutputStreamWriter( new FileOutputStream( new File( site_folder, "index.html" )), "UTF-8" ));
			
			SimpleDateFormat title_date_format 	= new SimpleDateFormat("yyyy/MM/dd HH:mm:ss zzz", Locale.US );
			SimpleDateFormat item_date_format 	= new SimpleDateFormat("yyyy/MM/dd", Locale.US );

			title_date_format.setTimeZone(TimeZone.getTimeZone("GMT"));
			item_date_format.setTimeZone(TimeZone.getTimeZone("GMT"));
				
			String website_title = mapping.getSiteName();
			
			if ( website_title == null ){
				
				website_title = channel_title;
			}
			
			String title_date = title_date_format.format(new Date( now ));
			
			String page_title = website_title + ": updated on " + title_date;
			
			String torrent_title = "WebSite for '" + website_title + "': updated on " + title_date;

			String NL = "\r\n";
			
			try{
				index.println( 
						"<html><head>" + NL + 
						"<meta http-equiv=\"content-type\" content=\"text/html; charset=UTF-8\">" + NL + 
						"<script src=\"resources/js/jquery.min.js\"></script>" + NL + 
						"<script src=\"resources/js/jquery.tablesorter.js\"></script>" + NL + 
						"<link rel=\"stylesheet\" type=\"text/css\" href=\"resources/css/style.css\" media=\"screen\" />" + NL + 
						"<title>" +	escape( website_title) + "</title>" +  NL + 
						"</head><body>" );
				
				index.println( "<h2>" + escape( page_title ) + "</h2>" );
				
				index.println( "<script src=\"resources/js/init.js\"></script>" );
				
				index.println( "<table id=\"content\" class=\"tablesorter\">" );

				index.println( "<thead><tr>" +
					"<th width=\"10%\">Thumb</th>" +
					"<th width=\"30%\">Title</th>" +
					"<th width=\"20%\">Detail</th>" +
					"<th width=\"8%\">Date</th>" +
					"<th width=\"5%\">Size</th>" +
					"<th width=\"7%\">Seeds/Peers</th>" +
					"<th width=\"5%\">CDP</th>" +
					"<th width=\"5%\">Download</th>" +
					"<th width=\"5%\">Torrent</th>" +
					"<th width=\"5%\">Play</th>" +
					"</tr></thead><tbody>" );
					
				int	row_num = 0;
				
				int	items_to_retain = mapping.getItemsToRetain();
				
				for ( File item: items ){
					
					row_num++;

					if ( row_num > items_to_retain ){
						
						log( "Deleting old item: " + item );
						
						FileUtil.recursiveDeleteNoCheck( item );
						
						continue;
					}
					
					try{
						Map config = FileUtil.readResilientFile( new File( item, "item.config" ));
						
						if ( config.isEmpty()){
							
							continue;
						}
						
						String 	title 		= MapUtils.getMapString( config, "title", null );
						long	time		= (Long)config.get( "time" );
						String 	description	= MapUtils.getMapString( config, "description", null );
						String 	hash_str	= MapUtils.getMapString( config, "hash", null );
						String 	dl_link 	= MapUtils.getMapString( config, "dl_link", null );
						String 	cdp_link 	= MapUtils.getMapString( config, "cdp_link", null );
						long	size		= (Long)config.get( "size" );
						long	seeds		= (Long)config.get( "seeds" );
						long	leechers	= (Long)config.get( "leechers" );
						
						String thumb 	= MapUtils.getMapString( config, "thumb", null );
						String torrent 	= MapUtils.getMapString( config, "torrent", null );

						if ( thumb != null ){

							File thumb_file = new File( item, thumb );
							
							FileUtil.copyFile( thumb_file, new File( site_folder, thumb_file.getName()));
						}
						
						if ( torrent != null ){

							File torrent_file = new File( item, torrent );
							
							FileUtil.copyFile( torrent_file, new File( site_folder, torrent_file.getName()));
							
								// migration - this is now computed during item extraction but for a while
								// existing items will have it missing
							
							if ( hash_str == null || hash_str.length() == 0 ){
								
								try{
									byte[] b_hash = TOTorrentFactory.deserialiseFromBEncodedFile( torrent_file ).getHash();
									
									hash_str = ByteFormatter.encodeString( b_hash );
									
								}catch( Throwable e ){
								}
							}
						}						
					
						associations_to_check.add( hash_str );
																		
						String row_str = String.valueOf( row_num );
						while( row_str.length() < 6 ){
							row_str = "0" + row_str;
						}
						index.println( "<tr>" );
						
							// thumb
						
							// use hidden text values to force sort order
						index.println( "<td><span style=\"display: none\">" + row_str + " </span>" );
						
						if ( thumb != null ){
							
							index.println( "<img src=\"" + thumb + "\">" );
						}
						
						index.println( "</td>" );

							// title
						
						index.println( "<td>" + escape( title ) + "</td>" );
						
							// desc
						
						index.println( "<td>" );
						
						if ( description != null ){
							
							String str = escape( description );
							
							str = str.replaceAll( "&lt;br&gt;", "<br>" );
							str = str.replaceAll( "&lt;p&gt;", "<p>" );
							str = str.replaceAll( "&lt;hr&gt;", "<hr>" );
							
							str = str.replaceAll( "&lt;b&gt;", "<b>" );
							str = str.replaceAll( "&lt;/b&gt;", "</b>" );
							
							str = str.replaceAll( "&lt;i&gt;", "<i>" );
							str = str.replaceAll( "&lt;/i&gt;", "</i>" );

							str = str.replaceAll( "&lt;ol&gt;", "<ol>" );
							str = str.replaceAll( "&lt;/ol&gt;", "</ol>" );
							str = str.replaceAll( "&lt;ul&gt;", "<ul>" );
							str = str.replaceAll( "&lt;/ul&gt;", "</ul>" );
							str = str.replaceAll( "&lt;li&gt;", "<li>" );
							str = str.replaceAll( "&lt;/li&gt;", "</li>" );
						
							index.println( str );
						}
						
							// date
						
						index.println( "<td>" + item_date_format.format( new Date( time )) + "</td>" );
				
							// size 
						
						String size_str = String.valueOf( size );
						while( size_str.length() < 12 ){
							size_str = "0" + size_str;
						}
						
						index.println( "<td><span style=\"display: none\">" + size_str + " </span>" + DisplayFormatters.formatByteCountToKiBEtc(size) + "</td>" );
						
							// seeds/peers
						
						index.println( "<td>" );
						
						if ( seeds >= 0 || leechers >= 0 ){
							
							seeds 		= Math.max(0, seeds);
							leechers 	= Math.max(0, leechers);
							
							String sl_str = String.valueOf( seeds+leechers );
							while( sl_str.length() < 7 ){
								sl_str = "0" + sl_str;
							}

							index.println( "<span style=\"display: none\">" + sl_str + " </span>" + seeds + "/" + leechers );
						}

						index.println( "</td>" );

							// cdp
													
						index.println( "<td>" );
						
						if ( cdp_link != null ){
							
							index.println( "<a href=\"" + cdp_link + "\">details</a>" );
						}
						
						index.println( "</td>" );
						
							// download
							
						index.println( "<td>" );
						
						if ( dl_link != null && torrent == null ){
							
							index.println( "<a href=\"" + dl_link + "\">download</a>" );
						}
						
						index.println( "</td>" );

							// torrent
						
						index.println( "<td>" );
						
						if ( torrent != null ){
							
							index.println( "<a href=\"" + torrent + "\">torrent</a>" );
						}
						
						index.println( "</td>" );

							// play
						
						index.println( "<td>" );
						
						if ( torrent != null ){
							
							index.println( "<a href=\"content?vuze_source=" + torrent + "\">play (torrent)</a>" );
							
						}else if ( dl_link != null ){
							
							if ( dl_link.startsWith( "magnet" )){
								
								index.println( "<a href=\"content?vuze_source=" + UrlUtils.encode( dl_link ) + "\">play (magnet)</a>" );
							}
						}
						
						index.println( "</td>" );

						index.println( "</tr>" );
						
					}catch( Throwable e ){
						
						Debug.out( e );
					}
				}
				
				index.println( "</tbody></table>" );

				index.println( "</body></html>" );
				
			}finally{
				
				index.close();
			}
						
			TOTorrentCreator tc = TOTorrentFactory.createFromFileOrDirWithComputedPieceLength( site_folder, TorrentUtils.getDecentralisedEmptyURL());
						
			TOTorrent torrent = tc.create();
			
			byte[] hash = torrent.getHash();
			
			TorrentUtils.setDecentralised( torrent );

			int primary_file_index = 0;
			
			TOTorrentFile[] files = torrent.getFiles();
			
			for ( TOTorrentFile file: files ){
				
				byte[][] comps = file.getPathComponents();
				
				if ( comps.length == 1 ){
					
					String str = new String(comps[0], "UTF-8" );
					
					if ( str.equals( "index.html" )){
						
						primary_file_index = file.getIndex();
						
						break;
					}
				}
			}
			
			PlatformTorrentUtils.setContentTitle( torrent, torrent_title );
			PlatformTorrentUtils.setContentPrimaryFileIndex( torrent, primary_file_index );
			
			String chat_url = inst.getURL();
			
			torrent.setComment( "See " + chat_url + " for updates");
			
			File torrent_file = new File( site_folder.getParent(), site_folder.getName() + ".torrent" );
			
			torrent.serialiseToBEncodedFile( torrent_file );
			
			GlobalManager global_manager = CoreFactory.getSingleton().getGlobalManager();
			
			DownloadManager new_dm = global_manager.addDownloadManager(
					torrent_file.toString(),
					hash,
					site_folder.toString(),
					DownloadManager.STATE_QUEUED, 
					true, // persistent 
					true,
					new DownloadManagerInitialisationAdapter() {
						
						@Override
						public void
						initialised(
							DownloadManager 	new_dm, 
							boolean 			for_seeding) 
						{
							try{
								TagType tt = TagManagerFactory.getTagManager().getTagType( TagType.TT_DOWNLOAD_MANUAL );
								
								String tag_name = "RSSToChat: Web Sites";
								
								Tag tag = tt.getTag( tag_name, true );
								
								if ( tag == null ){
									
									tag = tt.createTag( tag_name, true );
								}
								
								tag.addTaggable( new_dm );
								
							}catch( Throwable e ){
								
							}
							
							if ( mapping.getNetwork() != AENetworkClassifier.AT_PUBLIC ){
								
								DownloadManagerState state = new_dm.getDownloadState();
								
								state.setNetworkEnabled( AENetworkClassifier.AT_PUBLIC , false );
								
								for ( String net: AENetworkClassifier.AT_NON_PUBLIC ){
									
									state.setNetworkEnabled( net, true );
								}
							}
						}
						
						@Override
						public int
						getActions() 
						{
							return( ACT_ASSIGNS_TAGS );
						}
					} );
			
			String history_key = history.getHistoryKey();
			
			DownloadManagerState dm_state = new_dm.getDownloadState();
			
			dm_state.setFlag( DownloadManagerState.FLAG_ONLY_EVER_SEEDED, true );
			dm_state.setFlag( DownloadManagerState.FLAG_DISABLE_AUTO_FILE_MOVE, true );
			
			PluginCoreUtils.wrap( new_dm ).setAttribute( ta_website, history_key );
			
			new_dm.setForceStart( true );					
			
			log( "Torrent created: " + torrent_file );
			
			String magnet = buildMagnetHead( null, null, Base32.encode( hash ), torrent_title );
			
			magnet += "&xl="  + torrent.getSize();
			magnet += "&pfi=" + primary_file_index;
			
			if ( mapping.getNetwork() != AENetworkClassifier.AT_PUBLIC ){
				
				magnet += "&net=I2P";
			}
			
			magnet += "[[$dn]]";
			
			inst.sendMessage( magnet, new HashMap<String, Object>());

			log( "Posted site update" );
			
			List<DownloadManager> dms = global_manager.getDownloadManagers();
			
			List<DownloadManager>	my_dms = new ArrayList<DownloadManager>();
			
			for ( DownloadManager dm: dms ){
				
				String key = PluginCoreUtils.wrap( dm ).getAttribute( ta_website );
				
				if ( key != null && key.equals( history_key )){
				
					my_dms.add( dm );
				}
			}
			
			Collections.sort(
				my_dms,
				new Comparator<DownloadManager>()
				{
					@Override
					public int
					compare(
						DownloadManager dm1,
						DownloadManager dm2) 
					{
						long t1 = dm1.getDownloadState().getLongParameter( DownloadManagerState.PARAM_DOWNLOAD_ADDED_TIME );
						long t2 = dm2.getDownloadState().getLongParameter( DownloadManagerState.PARAM_DOWNLOAD_ADDED_TIME );
						
						if ( t1 < t2 ){
							
							return( 1 );
							
						}else if ( t1 > t2 ){
							
							return( -1 );
							
						}else{
							
							return( dm1.getInternalName().compareTo( dm2.getInternalName()));
						}
					}
				});
				
			int num_websites_to_retain = mapping.getSitesToRetain();
			
			for ( int i=0;i<my_dms.size();i++ ){
				
				DownloadManager dm = my_dms.get(i);
				
				long added = dm.getDownloadState().getLongParameter( DownloadManagerState.PARAM_DOWNLOAD_ADDED_TIME );

				if ( now - added < 24*60*60*1000 ){
					
					continue;
				}
				
				if ( i >= num_websites_to_retain ){
					
					ManagerUtils.asyncStopDelete( dm, DownloadManager.STATE_STOPPED, true, true, null );
					
					new File( dm.getSaveLocation() + ".torrent" ).delete();
					
					log( "Removed old web site: " + dm.getDisplayName());
				}
			}
			
				// do this in reverse so that the most recent association is checked last and therefore 
				// added to the subscription last (in case old ones are discarded)
			
			Collections.reverse( associations_to_check );
			
			for ( String hash_str: associations_to_check ){
				
				checkItemAssociations( mapping, hash_str );
			}
		}catch( Throwable e ){
			
			log( "Failed to update website", e );
		}
	}
	
	private void
	checkItemAssociations(
		Mapping		mapping,
		String		hash_str )
	{
		byte[] hash = UrlUtils.decodeSHA1Hash( hash_str );
					
		checkItemAssociations( mapping, hash );
	}
	
	private void
	checkItemAssociations(
		Mapping		mapping,
		byte[]		hash )
	{
		if ( hash != null ){
			
			List<Subscription> subs = mapping.getItemAssociations();
			
			for ( Subscription sub: subs ){
				
				if ( !sub.hasAssociation(hash)){
				
					sub.addAssociation( hash );
					
					log( "        Added item association " + ByteFormatter.encodeString( hash ) + " to " + sub.getName());
				}
			}
		}
	}
	
	private String
	escape(
		String	str )
	{
		return( XUXmlWriter.escapeXML( str ));
	}
	
	private String
	encodeTitle(
		String		title,
		String		dl_link,
		String		cdp_link )
	{
		if ( title.endsWith( "..." )){
			
			return( UrlUtils.encode( title ));
		}
		
		int	baggage = (dl_link==null?0:UrlUtils.encode(dl_link).length()) + (cdp_link==null?0:UrlUtils.encode(cdp_link).length());
		
		int MAX_TITLE = 240 - (3*baggage)/5;	// 100->180, 200->120 ...
		
		MAX_TITLE = Math.min( Math.max( MAX_TITLE, 80 ), 180 );
		
		boolean	truncated = false;
		
		String str;
		
		while( true ){
		
			str = UrlUtils.encode( title );
			
			if ( str.length() <= MAX_TITLE ){
				
				break;
			}
			
			title = title.substring( 0, title.length() - 1 );
			
			if ( !truncated ){
			
				truncated = true;
				
				MAX_TITLE -= 3;
			}
		}
		
		if ( truncated ){
			
			str += "...";
		}
		
		return( str );
	}
	
	private String
	buildMagnetHead(
		String		dl_link,
		String		cdp_link,
		String		hash,
		String		title )
	{
		String magnet;
		
		if ( dl_link != null && dl_link.toLowerCase(Locale.US).startsWith( "magnet:" )){
			
			magnet = dl_link;
			
		}else{
		
			magnet = 
				"magnet:?xt=urn:btih:" + hash + 
				"&dn=" + encodeTitle( title, dl_link, cdp_link ) + 
				(dl_link==null?"":("&fl=" + UrlUtils.encode( dl_link )));
		}
		
		return( magnet );
	}
	
	private String
	buildMagnetTail(
		String		magnet,
		String		dl_link,
		String		cdp_link,
		String		title,
		long		size,
		long		time,
		long		seeds,
		long		leechers )
		
	{
		final int MAX_CDP_LINK	= 140;
		
		int	length_rem = MAX_MESSAGE_SIZE;
		
		String lc_magnet = magnet.toLowerCase( Locale.US );
		
		if ( !lc_magnet.contains( "&dn=" )){	
			magnet += "&dn=" + encodeTitle( title, dl_link, cdp_link );
		}
		
		if ( size != -1 ){
			if ( !lc_magnet.contains( "&xl=" )){
				magnet += "&xl="  + size;
			}
		}
		
		if ( seeds != -1 ){
			magnet += "&_s="  + seeds;
		}
		
		if ( leechers != -1 ){
			magnet += "&_l="  + leechers;
		}
		
		if ( time > 0 ){
			magnet += "&_d="  + time;
		}
		
		boolean	has_cdp = cdp_link != null && ( dl_link == null || !cdp_link.equals( dl_link ));
		
		if ( has_cdp ){
			
			String encoded_cdp = UrlUtils.encode( cdp_link );
			
			if ( encoded_cdp.length() > MAX_CDP_LINK ){
				
				has_cdp = false;
				
			}else{
			
				magnet += "&_c=" + encoded_cdp;
			}
		}
		
		length_rem -= magnet.length();
		
		final String tail_min = "[[$dn]]";
		
		String tail = tail_min;
		
		String info = "";
		
		if ( size > 0 ){
			
			info = DisplayFormatters.formatByteCountToKiBEtc( size );
		}
		
		if ( time > 0 ){
			info += (info==""?"":", ")+new SimpleDateFormat( "yyyy/MM/dd").format( new Date( time ));
		}

		if ( has_cdp ){
			
			info += (info==""?"":", ") + "\"$_c[[details]]\"";
		}
		
		if ( info != "" ){
			
			tail += " (" + info + ")";
		}
		
		if ( tail.length() < length_rem ){
			
			magnet += tail;
			
		}else{
			
			magnet += tail_min;
		}
		
		return( magnet );
	}
	
	private byte[]
	getKey(
		String		str )
	{
		try{
			byte[] temp = new SHA1Simple().calculateHash( str.getBytes( "UTF-8" ));
			
			byte[] result = new byte[8];
			
			System.arraycopy( temp, 0, result, 0, 8 );
			
			return( result );
			
		}catch( Throwable e){
	
			Debug.out( e );
			
			return( new byte[8] );
		}
	}
	
	@Override
	public void
	unload() 
			
		throws PluginException
	{
		if ( config_model != null ){
			
			config_model.destroy();
			
			config_model = null;
		}
		
		if ( view_model != null ){
			
			view_model.destroy();
			
			view_model = null;
		}
		
		synchronized( mappings ){
			
			unloaded	= true;
			
			if ( timer != null ){
				
				timer.cancel();
				
				timer = null;
			}
			
			for ( Mapping mapping: mappings ){
				
				mapping.destroy();
			}
			
			mappings.clear();
		}
	}
	
	private class
	Mapping
	{
		private static final int	TYPE_NORMAL			= 1;
		private static final int	TYPE_READ_ONLY		= 2;
		private static final int	TYPE_ADMIN			= 3;
		
		private final String		source;
		private final boolean		is_rss;
		private final Pattern		desc_link_pattern;
		private final String		link_type;
		private final boolean		ignore_dates;
		private final int			min_seeds;
		private final int			min_leechers;
		private final int			type;
		private final String		nick;
		private final String		network;
		private final String		key;
		
		private final List<Subscription>	item_associations;
		
		private final String		presentation;
		private final String		website_name;
		private final int			retain_sites;
		private final int			retain_items;
		
		private final int			refresh;
		
		private ChatInstance	chat;
		private boolean			updating;
		private boolean			retry_outstanding;
		
		private boolean	destroyed;
		
		private
		Mapping(
			String				_source,
			boolean				_is_rss,
			Pattern				_desc_link_pattern,
			String				_link_type,
			boolean				_ignore_dates,
			int					_min_seeds,
			int					_min_leechers,
			String				_network,
			String				_key,
			int					_type,
			String				_nick,
			String				_presentation,
			String				_website_name,
			int					_retain_sites,
			int					_retain_items,
			List<Subscription>	_item_associations,
			int					_refresh )
		{
			source				= _source;
			is_rss				= _is_rss;
			desc_link_pattern	= _desc_link_pattern;
			link_type			= _link_type;
			ignore_dates		= _ignore_dates;
			min_seeds			= _min_seeds;
			min_leechers		= _min_leechers;
			network				= _network;
			key					= _key;
			type				= _type;
			nick				= _nick;
			
			presentation		= _presentation;
			website_name		= _website_name;
			retain_sites		= _retain_sites;
			retain_items		= _retain_items;
			item_associations	= _item_associations;
			
			refresh				= _refresh;
			
			retry_outstanding = true;		// initial load regardless
		}
		
		private void
		update(
			BuddyPluginBeta		bp,
			int					minute_count,
			boolean				force )
		{
			synchronized( this ){
				
				if ( updating ){
					
					return;
				}
				
				updating = true;
			}
			
			try{
				if ( 	force ||
						retry_outstanding || 
						minute_count % refresh == 0 ){
									
					retry_outstanding = false;
					
					History history = new History( this );

					log( "Refreshing " + getSourceName() + " (" + history.getHistoryKey() + ")");
					
					ChatInstance chat_instance;
										
					synchronized( this ){
						
						if ( destroyed ){
							
							log( "Mapping destroyed" );
							
							return;
						}
						
						if ( chat == null || chat.isDestroyed()){
							
							chat = null;
							
							try{
								if ( type != TYPE_NORMAL ){
									
									List<ChatInstance> chats = bp.getChats();

									for ( ChatInstance inst: chats ){
										
										if ( type == TYPE_ADMIN ){
											
											if ( inst.isManagedFor( network, key )){
											
												chat = inst;
												
												break;
											}
										}else if ( type == TYPE_READ_ONLY ){
										
											if ( inst.isReadOnlyFor( network, key )){
											
												chat = inst;
												
												break;
											}
										}
									}
								}
								
								if ( chat == null ){
									
									chat = bp.getChat( network, key );
									
									if ( chat == null ){
										
										retry_outstanding = true;
										
										return;
									}
									
									if ( type == TYPE_ADMIN ){
										
										ChatInstance man_inst = chat.getManagedChannel();
										
										chat.destroy();
										
										chat	= man_inst;
										
									}else  if ( type == TYPE_READ_ONLY ){
										
										ChatInstance ro_inst = chat.getReadOnlyChannel();
										
										chat.destroy();
										
										chat	= ro_inst;
									}
								}
								
								chat.setFavourite( true );
								
								chat.setSaveMessages( true );
									
								if ( nick != null ){
									
									chat.setSharedNickname( false );
									
									chat.setInstanceNickname( nick );
								}
								
								log( "Chat initialised for '" + getChatName() + "': URL=" + chat.getURL() + ", history=" + history.getFileName());

							}catch( Throwable e ){
								
								log( "Failed to create chat '" + getChatName() + "': " + Debug.getNestedExceptionMessage( e ));
								
								return;
							}
						}
						
						chat_instance = chat;						
					}
								
					try{
						if ( is_rss ){
							
							retry_outstanding = updateRSS( this, source, chat_instance, history, force );
							
						}else{
							
							retry_outstanding = updateSubscription( this, source, chat_instance, history, force );
						}
					}finally{
						
						history.save();
					}
				}
			}finally{
				
				synchronized( this ){
					
					updating = false;
				}
			}
		}
		
		private void
		destroy()
		{
			synchronized( this ){

				destroyed = true;
				
				if ( chat != null ){
				
					chat.destroy();
				
					chat = null;
				}
			}	
		}
		
		private String
		getOverallName()
		{
			String type_str;
			
			if ( type == TYPE_NORMAL ){
				type_str = "normal";
			}else if ( type == TYPE_READ_ONLY ){
				type_str = "readonly";
			}else{
				type_str = "admin";
			}
			
			return( getSourceName() + ", " + getChatName() + ", type=" + type_str + ", refresh=" + refresh + " min" );
		}
		
		private String
		getPresentation()
		{
			return( presentation );
		}
		
		public String
		getSiteName()
		{
			return( website_name );
		}
		
		private int
		getSitesToRetain()
		{
			return( retain_sites );
		}
		
		private int
		getItemsToRetain()
		{
			return( retain_items );
		}
		
		private boolean
		getIgnoreDates()
		{
			return( ignore_dates );
		}
		
		private int
		getMinSeeds()
		{
			return( min_seeds );
		}
		
		private int
		getMinLeechers()
		{
			return( min_leechers );
		}
		
		private String
		getNetwork()
		{
			return( network );
		}
		
		private String
		getSourceName()
		{
			return( ( is_rss?"RSS":"Subscription") + ": " + source );
		}
		
		private String
		getChatName()
		{
			return((network==AENetworkClassifier.AT_PUBLIC?"Public":"Anonymous") + ": " + key );
		}
		
		private List<Subscription>
		getItemAssociations()
		{
			return( item_associations );
		}
	}
	
	private class
	History
	{
		private String	history_key;
		
		private File	dir;
		private File 	file;
		
		private long 	latest_publish;
		
		private Map<HashWrapper,String>	history =
				new LinkedHashMap<HashWrapper,String>(MAX_HISTORY_ENTRIES,0.75f,false)
				{
					@Override
					protected boolean
					removeEldestEntry(
				   		Map.Entry<HashWrapper,String> eldest) 
					{
						return size() > MAX_HISTORY_ENTRIES;
					}
				};
		
		private boolean dirty;
		
		private
		History(
			Mapping		mapping )
		{
			String	key = mapping.getSourceName() + "/" + mapping.getChatName();
			
			history_key = Base32.encode( getKey( key ));
			
			try{				
				dir 	= new File( history_dir, history_key );
				file 	= new File( history_dir, history_key  + ".dat" );
				
			}catch( Throwable e ){
				
				Debug.out( e );
			}
			
			if ( file.exists()){
				
				Map map = FileUtil.readResilientFile( file );
				
				Long lp = (Long)map.get( "last_publish" );
				
				if ( lp != null ){
					
					latest_publish = lp.longValue();
				}
				
				List<byte[]> l = (List<byte[]>)map.get( "ids" );
				
				for ( byte[] id: l ){
					
					history.put( new HashWrapper( id ), "" );
				}
			}
		}
		
		private String
		getHistoryKey()
		{
			return( history_key );
		}
		
		private File
		getItemsFolder()
		{			
			File result = new File( dir, "items" );
						
			if ( !result.exists()){
				
				result.mkdirs();
			}

			return( result );
		}
		
		private File
		getItemFolder(
			String		id,
			long		time )
		{			
			File result = new File( dir, "items" );
			
			final String prefix = new SimpleDateFormat( "yyyyMMdd").format( new Date( time )) + "_" + Base32.encode( getKey( id )) + "_";
			
			if ( time == 0 ){
			
				File[] match = 
					result.listFiles(
						new FilenameFilter() {
							
							@Override
							public boolean
							accept(
								File dir, 
								String filename) 
							{
								return( filename.startsWith( prefix ));
							}
						});
				
				if ( match != null && match.length == 1 ){
					
					return( match[0] );
				}
				
				time = SystemTime.getCurrentTime();
			}
			
			result = new File( result, prefix + time );
			
			if ( !result.exists()){
				
				result.mkdirs();
			}

			return( result );
		}
		
		private File
		getSitesFolder()
		{			
			File result = new File( dir, "sites" );
						
			if ( !result.exists()){
				
				result.mkdirs();
			}

			return( result );
		}
		
		private File
		getSiteFolder(
			long		time )
		{			
			File result = new File( dir, "sites" );
				
			result = new File( result, new SimpleDateFormat( "yyyyMMdd").format( new Date( time )) + "_" + history_key + "_" + time );

			if ( !result.exists()){
				
				result.mkdirs();
			}

			return( result );
		}
		
		private String
		getFileName()
		{
			return( file.getName());
		}
		
		private long
		getLatestPublish()
		{
			return( latest_publish );
		}
		
		private boolean
		hasPublished(
			String		id )
		{
			return( history.containsKey( new HashWrapper( getKey( id ))));
		}

		private void
		setPublished(
			String		id,
			long		item_time )
		{
			history.put( new HashWrapper( getKey( id )), "" );
			
			if ( item_time > latest_publish ){
				
				latest_publish = item_time;
			}
			
			dirty = true;
		}
		
		private void
		save()
		{
			if ( dirty ){
				
				Map map = new HashMap();
				
				map.put( "last_publish", latest_publish );
				
				List	l = new ArrayList( history.size());
				
				map.put( "ids", l );
				
				for ( HashWrapper k: history.keySet()){
					
					l.add( k.getBytes());
				}
				
				FileUtil.writeResilientFile( file, map );
			}
		}
	}
}
